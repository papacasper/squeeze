package com.papacasper.squeeze

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.TransformationRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object VideoCompressor {

    private const val MAX_ATTEMPTS = 8
    private const val MIN_BITRATE = 100_000L

    // Some devices' hardware HEVC encoders deadlock partway through a transform and never
    // call onCompleted/onError. If getProgress() reports the same value for this long, treat
    // it as stalled, cancel, and retry the pass with AVC (H.264) instead.
    private const val STALL_TIMEOUT_MS = 20_000L

    private class StallException : Exception("Encoder stalled")

    // Many hardware encoders clamp bitrate to a device/resolution-specific floor and
    // silently ignore a lower request. If bitrate reduction alone isn't shrinking the
    // output, step resolution down this ladder too so the encoder's floor drops with it.
    private val HEIGHT_LADDER = intArrayOf(1080, 720, 540, 480, 360, 240)

    /**
     * Re-encodes the video, retrying at progressively lower bitrates (and, once that stops
     * helping, lower resolutions) until the output is at or under targetBytes or the retry
     * budget is exhausted. Keeps the smallest result seen in case the target can't be reached.
     */
    suspend fun compress(
        context: Context,
        sourceUri: Uri,
        targetBytes: Long,
        outputFile: File,
        onProgress: (String, Float) -> Unit
    ): File {
        val durationMs = getDurationMs(context, sourceUri)
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val originalHeight = getVideoHeight(context, sourceUri)

        // Reserve ~12% of the target for audio + container overhead.
        val videoTargetBits = (targetBytes * 8 * 0.88 / durationSec).toLong()
        var bitrate = videoTargetBits.coerceIn(MIN_BITRATE, 20_000_000L)

        var bestFile: File? = null
        var bestBytes = Long.MAX_VALUE
        var previousPassBytes = Long.MAX_VALUE
        var ladderIndex = 0

        for (attempt in 1..MAX_ATTEMPTS) {
            val targetHeight = HEIGHT_LADDER.getOrElse(ladderIndex) { HEIGHT_LADDER.last() }
                .coerceAtMost(originalHeight)
            val passBase = (attempt - 1).toFloat() / MAX_ATTEMPTS
            onProgress(
                "Encoding pass $attempt of $MAX_ATTEMPTS (target ${bitrate / 1000} kbps, ${targetHeight}p)...",
                passBase
            )
            val passFile = File(outputFile.parentFile, "pass${attempt}_${outputFile.name}")
            try {
                try {
                    transcode(context, sourceUri, passFile, bitrate, targetHeight, originalHeight, "video/hevc") { intraFraction ->
                        onProgress(
                            "Encoding pass $attempt of $MAX_ATTEMPTS (target ${bitrate / 1000} kbps, ${targetHeight}p)...",
                            passBase + intraFraction / MAX_ATTEMPTS
                        )
                    }
                } catch (e: StallException) {
                    // HEVC hardware encoder deadlocked — fall back to AVC for this pass.
                    onProgress(
                        "Encoder stalled, retrying pass $attempt with H.264...",
                        passBase
                    )
                    transcode(context, sourceUri, passFile, bitrate, targetHeight, originalHeight, "video/avc") { intraFraction ->
                        onProgress(
                            "Encoding pass $attempt of $MAX_ATTEMPTS (target ${bitrate / 1000} kbps, ${targetHeight}p, H.264)...",
                            passBase + intraFraction / MAX_ATTEMPTS
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                passFile.delete()
                bestFile?.delete()
                throw e
            } catch (e: Exception) {
                passFile.delete()
                if (bestFile != null) break
                throw e
            }
            val passBytes = passFile.length()

            if (passBytes in 1 until bestBytes) {
                bestFile?.delete()
                bestFile = passFile
                bestBytes = passBytes
            } else {
                passFile.delete()
            }

            if (passBytes in 1..targetBytes) break

            // If the last resolution step barely moved the needle, the bitrate request is
            // being clamped by the encoder — escalate resolution downscaling instead.
            val shrinkRatio = if (previousPassBytes < Long.MAX_VALUE && previousPassBytes > 0) {
                passBytes.toDouble() / previousPassBytes.toDouble()
            } else 0.0
            if (attempt > 1 && shrinkRatio > 0.9 && ladderIndex < HEIGHT_LADDER.lastIndex) {
                ladderIndex++
            }
            previousPassBytes = passBytes

            if (bitrate <= MIN_BITRATE && ladderIndex == HEIGHT_LADDER.lastIndex) {
                onProgress("Reached minimum bitrate and resolution; can't shrink further.", 1f)
                break
            }

            // Oversized: scale bitrate down proportionally, with extra headroom each retry.
            val ratio = if (passBytes > 0) targetBytes.toDouble() / passBytes.toDouble() else 0.5
            bitrate = (bitrate * ratio * 0.85).toLong().coerceAtLeast(MIN_BITRATE)
        }

        val result = bestFile ?: throw IllegalStateException("Video compression failed to produce output")
        if (result != outputFile) {
            result.copyTo(outputFile, overwrite = true)
            result.delete()
        }
        return outputFile
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcode(
        context: Context,
        sourceUri: Uri,
        outFile: File,
        bitrate: Long,
        targetHeight: Int,
        originalHeight: Int,
        videoMimeType: String,
        onPassProgress: (Float) -> Unit
    ) {
        if (outFile.exists()) outFile.delete()

        val result: Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val outerScope = this
        suspendCancellableCoroutine { cont ->
            val request = TransformationRequest.Builder()
                .setVideoMimeType(videoMimeType)
                .build()

            lateinit var transformer: Transformer
            lateinit var pollJob: kotlinx.coroutines.Job
            transformer = Transformer.Builder(context)
                .setTransformationRequest(request)
                .setEncoderFactory(
                    androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setBitrate(bitrate.toInt())
                                .build()
                        )
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        pollJob.cancel()
                        if (cont.isActive) cont.resume(Result.success(Unit))
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        pollJob.cancel()
                        if (cont.isActive) cont.resume(Result.failure(exportException))
                    }
                })
                .build()

            val mediaItem = MediaItem.fromUri(sourceUri)
            val itemBuilder = EditedMediaItem.Builder(mediaItem)
            if (targetHeight < originalHeight) {
                itemBuilder.setEffects(
                    Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight)))
                )
            }
            transformer.start(itemBuilder.build(), outFile.absolutePath)

            val progressHolder = ProgressHolder()
            pollJob = outerScope.launch {
                var lastProgress = -1
                var stalledForMs = 0L
                while (isActive) {
                    val progressState = transformer.getProgress(progressHolder)
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onPassProgress(progressHolder.progress / 100f)
                        if (progressHolder.progress == lastProgress) {
                            stalledForMs += 300
                            if (stalledForMs >= STALL_TIMEOUT_MS) {
                                transformer.cancel()
                                if (cont.isActive) cont.resume(Result.failure(StallException()))
                                return@launch
                            }
                        } else {
                            lastProgress = progressHolder.progress
                            stalledForMs = 0L
                        }
                    }
                    delay(300)
                }
            }

            cont.invokeOnCancellation {
                pollJob.cancel()
                transformer.cancel()
            }
        }
        }
        result.getOrThrow()
    }

    private fun getDurationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10_000L
        } finally {
            retriever.release()
        }
    }

    private fun getVideoHeight(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
        } finally {
            retriever.release()
        }
    }
}
