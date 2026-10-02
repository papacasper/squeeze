package com.papacasper.squeeze

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import android.util.Log
import android.os.SystemClock
import java.io.File
import kotlin.coroutines.resume

object VideoCompressor {

    private const val MAX_ATTEMPTS = 8

    // Used when the source has an audio track whose bitrate can't be read.
    private const val FALLBACK_AUDIO_BITRATE = 128_000L

    // Some devices' hardware HEVC encoders deadlock partway through a transform and never
    // call onCompleted/onError. If getProgress() reports the same value for this long, treat
    // it as stalled, cancel, and retry the pass with AVC (H.264) instead (see StallWatchdog).

    private const val POLL_MS = 300L

    // logcat tag for per-pass timing: adb logcat -s SqueezeTiming
    private const val TIMING_TAG = "SqueezeTiming"

    private class StallException : Exception("Encoder stalled")

    /** The pass was stopped early because it was heading well over the goal; [projectedBytes] is where it was heading. */
    private class OverBudgetException(val projectedBytes: Long) : Exception("Pass projected over budget")

    // Many hardware encoders clamp bitrate to a device/resolution-specific floor and silently
    // ignore a lower request, so a starved budget also steps resolution down (never below 720p).

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
        trimStartMs: Long = 0L,
        trimDurationMs: Long = 0L,
        floors: BitrateMath.Floors = BitrateMath.Floors(),
        onSettings: (String) -> Unit = {},
        onProgress: (String, Float) -> Unit
    ): File {
        val HEIGHT_LADDER = floors.ladder()
        DecodeCheck.problem(context, sourceUri)?.let { throw IllegalStateException(it) }
        val fullDurationMs = getDurationMs(context, sourceUri)
        val trimmed = trimDurationMs > 0L
        val durationMs = if (trimmed) trimDurationMs else fullDurationMs
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val originalHeight = getVideoHeight(context, sourceUri)
        val maxAttempts = AttemptBudget.forSource(getVideoWidth(context, sourceUri), getVideoHeightRaw(context, sourceUri), MAX_ATTEMPTS)

        // Audio is passed through untouched, so its size is fixed: budget it explicitly, then give
        // the video what's left of ~92% of the target (the rest covers container overhead).
        val sourceAudioBitrate = estimateAudioBitrate(context, sourceUri)
        // Audio too big for the budget is re-encoded smaller (null = copied untouched).
        val audioReencodeBitrate = BitrateMath.audioReencodeBitrate(sourceAudioBitrate, targetBytes, durationSec)
        var audioBitrate = audioReencodeBitrate
        var audioBytes = (audioBitrate ?: sourceAudioBitrate) * durationSec / 8.0
        val fileBytes = context.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length }
            ?.takeIf { it > 0 } ?: Long.MAX_VALUE
        // For a trimmed clip, only that share of the source's bytes is comparable to the output.
        val sourceBytes = if (trimmed && fileBytes != Long.MAX_VALUE) {
            (fileBytes * (durationMs.toDouble() / fullDurationMs.coerceAtLeast(1L))).toLong().coerceAtLeast(1L)
        } else fileBytes
        // A source already under the target must come out smaller than itself, not merely under the target.
        val goalBytes = if (sourceBytes <= targetBytes) (sourceBytes * 0.9).toLong() else targetBytes
        var bitrate = BitrateMath.initialVideoBitrate(targetBytes, durationSec, audioBytes, sourceBytes)

        var bestFile: File? = null
        var bestBytes = Long.MAX_VALUE
        var previousPassBytes = Long.MAX_VALUE
        // Start at a rung the requested bitrate can support, not always at 1080p.
        val sourcePixels = getVideoWidth(context, sourceUri).toLong() * getVideoHeightRaw(context, sourceUri)
        var ladderIndex = BitrateMath.startingLadderIndex(bitrate, sourcePixels, originalHeight, HEIGHT_LADDER)
        // Still starved at the chosen rung: cap to MIN_FPS (a no-op for sources already at or below it).
        var capFps = BitrateMath.starvedAt(bitrate, sourcePixels, originalHeight, HEIGHT_LADDER[ladderIndex])

        // The first codec to stall is dropped for the rest of the job, so later passes don't each re-pay the stall timeout.
        var videoMime = "video/hevc"
        val stallMessage = "The video encoder stopped responding, with both H.265 and H.264. " +
            "Try a shorter or lower-resolution video, or compress it on a PC with squeeze-cli."

        // A pass over a long clip costs minutes: encode a few seconds first and start from the
        // bitrate that sample says will land on the target. What the sample measured is remembered per source
        // so repeating the job skips it.
        if (durationSec >= BitrateMath.CALIBRATE_MIN_SEC) {
            val sampleSec = BitrateMath.CALIBRATE_SAMPLE_SEC
            val sampleHeight = HEIGHT_LADDER.getOrElse(ladderIndex) { HEIGHT_LADDER.last() }.coerceAtMost(originalHeight)
            val sampleFps = if (capFps) floors.minFps else null
            val cacheKey = CalibrationCache.Key(sourceUri.toString(), fileBytes, trimStartMs, trimDurationMs, sampleHeight, sampleFps, sampleSec)
            val cached = CalibrationCache.get(context.cacheDir, cacheKey)
            val sampleFile = File(outputFile.parentFile, "sample_${outputFile.name}")
            try {
                val sampleBytes: Long
                val sampleBitrate: Long
                val sampleAudio: Long
                if (cached != null) {
                    onProgress("Reusing the earlier sample for this file.", 0f)
                    Log.i(TIMING_TAG, "sample cached (${cached.sampleBytes} bytes at ${cached.bitrate / 1000} kbps)")
                    sampleBytes = cached.sampleBytes; sampleBitrate = cached.bitrate; sampleAudio = cached.audioBitrate
                } else {
                    onProgress("Testing a short sample to size the encode...", 0f)
                    val sampleStart = trimStartMs + ((durationSec - sampleSec) / 2 * 1000).toLong()
                    val t0 = SystemClock.elapsedRealtime()
                    try {
                        transcode(
                            context, sourceUri, sampleFile, bitrate, sampleHeight, originalHeight, sampleFps, audioBitrate, videoMime,
                            sampleStart, (sampleSec * 1000).toLong()
                        ) { }
                    } catch (e: StallException) {
                        videoMime = "video/avc"
                        Log.w(TIMING_TAG, "sample stalled on HEVC, using H.264 from here")
                        throw e
                    }
                    sampleBytes = sampleFile.length()
                    sampleBitrate = bitrate
                    sampleAudio = audioBitrate ?: sourceAudioBitrate
                    Log.i(TIMING_TAG, "sample ${SystemClock.elapsedRealtime() - t0} ms -> $sampleBytes bytes at ${bitrate / 1000} kbps, ${sampleHeight}p")
                    if (sampleBytes > 0) CalibrationCache.put(context.cacheDir, cacheKey, CalibrationCache.Entry(sampleBitrate, sampleBytes, sampleAudio))
                }
                if (sampleBytes > 0) {
                    val predicted = BitrateMath.predictedBytes(sampleBytes, sampleSec, sampleAudio, durationSec)
                    bitrate = BitrateMath.calibratedBitrate(sampleBitrate, sampleBytes, sampleSec, sampleAudio, durationSec, targetBytes)
                    onProgress("Sample predicts about ${predicted / 1_000_000} MB; starting at ${bitrate / 1000} kbps.", 0f)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                sampleFile.delete()
                throw e
            } catch (e: Exception) {
                // Calibration is only an optimisation; the passes below still verify the size.
            } finally {
                sampleFile.delete()
            }
        }
        var bestSettings = ""

        for (attempt in 1..maxAttempts) {
            val targetHeight = HEIGHT_LADDER.getOrElse(ladderIndex) { HEIGHT_LADDER.last() }
                .coerceAtMost(originalHeight)
            val cappedThisPass = capFps
            val audioLabel = audioBitrate?.let { ", audio ${it / 1000} kbps" } ?: ""
            val passBase = (attempt - 1).toFloat() / maxAttempts
            onProgress(
                "Encoding pass $attempt of $maxAttempts (target ${bitrate / 1000} kbps, ${targetHeight}p${if (capFps) ", ${floors.minFps.toInt()} fps" else ""}$audioLabel)...",
                passBase
            )
            val passFile = File(outputFile.parentFile, "pass${attempt}_${outputFile.name}")
            val passStart = SystemClock.elapsedRealtime()
            // A pass that can still be followed by a lower one may be stopped early; the last one, or one already at
            // the lowest bitrate, always runs to the end so there is a result to return.
            val abortOverGoal = if (attempt < maxAttempts && bitrate > BitrateMath.MIN_BITRATE) goalBytes else null
            var abortedProjection: Long? = null
            try {
                suspend fun encode(mime: String) {
                    val label = if (mime == "video/avc") ", H.264" else ""
                    transcode(context, sourceUri, passFile, bitrate, targetHeight, originalHeight, if (capFps) floors.minFps else null, audioBitrate, mime, trimStartMs, trimDurationMs, abortOverGoal) { intraFraction ->
                        onProgress(
                            "Encoding pass $attempt of $maxAttempts (target ${bitrate / 1000} kbps, ${targetHeight}p${if (capFps) ", ${floors.minFps.toInt()} fps" else ""}$audioLabel$label)...",
                            passBase + intraFraction / maxAttempts
                        )
                    }
                }
                try {
                    try {
                        encode(videoMime)
                    } catch (e: OverBudgetException) {
                        abortedProjection = e.projectedBytes
                    }
                } catch (e: StallException) {
                    if (videoMime == "video/avc") throw IllegalStateException(stallMessage)
                    // HEVC hardware encoder deadlocked: use AVC for this pass and every later one.
                    Log.w(TIMING_TAG, "pass $attempt stalled on HEVC after ${SystemClock.elapsedRealtime() - passStart} ms, switching to H.264")
                    videoMime = "video/avc"
                    onProgress("Encoder stalled, retrying pass $attempt with H.264...", passBase)
                    try {
                        encode(videoMime)
                    } catch (e2: StallException) {
                        throw IllegalStateException(stallMessage)
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
            val aborted = abortedProjection
            if (aborted != null) {
                passFile.delete()
                Log.i(
                    TIMING_TAG,
                    "pass $attempt stopped after ${SystemClock.elapsedRealtime() - passStart} ms: heading for $aborted bytes (goal $goalBytes) at ${bitrate / 1000} kbps"
                )
                if (attempt > 1 && BitrateMath.shrinkStalled(aborted, previousPassBytes) && ladderIndex < HEIGHT_LADDER.lastIndex) ladderIndex++
                previousPassBytes = aborted
                if (ladderIndex == HEIGHT_LADDER.lastIndex) capFps = true
                bitrate = BitrateMath.nextVideoBitrate(bitrate, aborted, goalBytes, audioBytes)
                continue
            }
            val passBytes = passFile.length()
            Log.i(
                TIMING_TAG,
                "pass $attempt ${SystemClock.elapsedRealtime() - passStart} ms ${if (videoMime == "video/avc") "avc" else "hevc"} " +
                    "${targetHeight}p ${if (cappedThisPass) "${floors.minFps.toInt()}fps" else "srcfps"} ${bitrate / 1000} kbps -> $passBytes bytes (goal $goalBytes)"
            )

            if (passBytes in 1 until bestBytes) {
                bestFile?.delete()
                bestFile = passFile
                bestBytes = passBytes
                bestSettings = "${targetHeight}p · " +
                    (if (cappedThisPass) "${floors.minFps.toInt()} fps" else "original frame rate") +
                    " · audio " + (audioBitrate?.let { "${it / 1000} kbps" } ?: "original")
            } else {
                passFile.delete()
            }

            if (passBytes in 1..goalBytes) break

            // If the last resolution step barely moved the needle, the bitrate request is
            // being clamped by the encoder — escalate resolution downscaling instead.
            if (attempt > 1 && BitrateMath.shrinkStalled(passBytes, previousPassBytes) && ladderIndex < HEIGHT_LADDER.lastIndex) {
                ladderIndex++
            }
            previousPassBytes = passBytes

            // At the resolution floor the only lever left besides bitrate is the frame rate.
            if (ladderIndex == HEIGHT_LADDER.lastIndex) capFps = true

            if (bitrate <= BitrateMath.MIN_BITRATE && ladderIndex == HEIGHT_LADDER.lastIndex && cappedThisPass) {
                // Video has nothing left to give; try squeezing the audio harder before giving up.
                val lowerAudio = BitrateMath.nextLowerAudioBitrate(audioBitrate, sourceAudioBitrate, floors)
                if (lowerAudio != null) {
                    audioBitrate = lowerAudio
                    audioBytes = lowerAudio * durationSec / 8.0
                    bitrate = BitrateMath.initialVideoBitrate(targetBytes, durationSec, audioBytes, sourceBytes)
                    continue
                }
                onProgress("Reached minimum bitrate and resolution; can't shrink further.", 1f)
                break
            }

            // Oversized: scale bitrate down proportionally, with extra headroom each retry.
            bitrate = BitrateMath.nextVideoBitrate(bitrate, passBytes, goalBytes, audioBytes)
        }

        onSettings(bestSettings)
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
        capFpsTo: Float?,
        audioBitrate: Long?,
        videoMimeType: String,
        trimStartMs: Long,
        trimDurationMs: Long,
        abortOverGoal: Long? = null,
        onPassProgress: (Float) -> Unit
    ) {
        if (outFile.exists()) outFile.delete()

        val result: Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val outerScope = this
        suspendCancellableCoroutine { cont ->
            lateinit var transformer: Transformer
            lateinit var pollJob: kotlinx.coroutines.Job
            transformer = Transformer.Builder(context)
                .setVideoMimeType(videoMimeType)
                .setEncoderFactory(
                    androidx.media3.transformer.DefaultEncoderFactory.Builder(context)
                        .apply {
                            // Non-default audio settings make Transformer re-encode audio instead of copying it.
                            if (audioBitrate != null) {
                                setRequestedAudioEncoderSettings(
                                    androidx.media3.transformer.AudioEncoderSettings.Builder()
                                        .setBitrate(audioBitrate.toInt())
                                        .build()
                                )
                            }
                        }
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

            val mediaItem = MediaItem.Builder().setUri(sourceUri).apply {
                if (trimDurationMs > 0L) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(trimStartMs)
                            .setEndPositionMs(trimStartMs + trimDurationMs)
                            .build()
                    )
                }
            }.build()
            val itemBuilder = EditedMediaItem.Builder(mediaItem)
            val videoEffects = buildList<androidx.media3.common.Effect> {
                if (targetHeight < originalHeight) add(Presentation.createForHeight(targetHeight))
                if (capFpsTo != null) add(androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(capFpsTo))
            }
            if (videoEffects.isNotEmpty()) itemBuilder.setEffects(Effects(emptyList(), videoEffects))
            transformer.start(itemBuilder.build(), outFile.absolutePath)

            val progressHolder = ProgressHolder()
            pollJob = outerScope.launch {
                val watchdog = StallWatchdog()
                while (isActive) {
                    val progressState = transformer.getProgress(progressHolder)
                    val stalled = when (progressState) {
                        Transformer.PROGRESS_STATE_AVAILABLE -> {
                            onPassProgress(progressHolder.progress / 100f)
                            watchdog.tick(progressHolder.progress, POLL_MS)
                        }
                        // Started but no first progress yet: counts against the (longer) start timeout.
                        Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> watchdog.tick(null, POLL_MS)
                        // UNAVAILABLE (unknown duration) never reports progress, so there is nothing to judge by.
                        else -> false
                    }
                    if (stalled) {
                        transformer.cancel()
                        if (cont.isActive) cont.resume(Result.failure(StallException()))
                        return@launch
                    }
                    if (abortOverGoal != null && progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val projected = PassProjection.abortWith(outFile.length(), progressHolder.progress / 100f, abortOverGoal)
                        if (projected != null) {
                            transformer.cancel()
                            if (cont.isActive) cont.resume(Result.failure(OverBudgetException(projected)))
                            return@launch
                        }
                    }
                    delay(POLL_MS)
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

    // Metadata reports the coded (pre-rotation) size, but Transformer's Presentation works on the
    // upright frame, so a portrait clip stored as 1920x1080 + 90deg rotation is really 1920 tall.
    private fun getVideoHeight(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: height
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) width else height
        } finally {
            retriever.release()
        }
    }

    private fun getVideoWidth(context: Context, uri: Uri): Int = metadataInt(context, uri, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
    private fun getVideoHeightRaw(context: Context, uri: Uri): Int = metadataInt(context, uri, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

    private fun metadataInt(context: Context, uri: Uri, key: Int): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(key)?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }

    /** What the pre-encode feasibility check needs from [uri]; null if it can't be read. */
    internal fun probe(context: Context, uri: Uri): SourceProbe? = try {
        SourceProbe(
            audioBitrate = estimateAudioBitrate(context, uri),
            pixels = getVideoWidth(context, uri).toLong() * getVideoHeightRaw(context, uri),
            height = getVideoHeight(context, uri)
        )
    } catch (e: Exception) {
        null
    }

    private fun estimateAudioBitrate(context: Context, uri: Uri): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    return if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    } else {
                        FALLBACK_AUDIO_BITRATE
                    }
                }
            }
            return 0L
        } catch (e: Exception) {
            return FALLBACK_AUDIO_BITRATE
        } finally {
            extractor.release()
        }
    }
}

internal class SourceProbe(val audioBitrate: Long, val pixels: Long, val height: Int)
