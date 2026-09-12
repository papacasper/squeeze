package com.papacasper.discordcompressor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream

object VideoToGifConverter {

    private const val MAX_ATTEMPTS = 6
    private const val MIN_FPS = 15
    private const val MIN_WIDTH = 160
    // GIFs get huge fast with length; cap the clip so output stays usable. Longer source
    // videos are trimmed via [trimStartMs] rather than converted in full.
    const val MAX_DURATION_MS = 30_000L

    private data class Params(val fps: Int, val width: Int)

    /**
     * Samples frames from the source video and encodes them as an animated GIF, retrying
     * at lower frame rate / resolution until the output is at or under targetBytes or the
     * retry budget is exhausted. Keeps the smallest result seen in case the target can't be hit.
     */
    suspend fun convert(
        context: Context,
        sourceUri: Uri,
        targetBytes: Long,
        outputFile: File,
        /** Offset into the source video, in ms, where the trimmed clip starts. */
        trimStartMs: Long = 0,
        /** Length of the trimmed clip, in ms; clamped to [MAX_DURATION_MS]. */
        trimDurationMs: Long = MAX_DURATION_MS,
        onProgress: (String, Float) -> Unit
    ): File {
        val retriever = MediaMetadataRetriever()
        val (durationMs, srcWidth, srcHeight) = try {
            retriever.setDataSource(context, sourceUri)
            val total = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000L
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 480
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 480
            // METADATA_KEY_VIDEO_WIDTH/HEIGHT report the coded (pre-rotation) frame size, but
            // getFrameAtTime() returns bitmaps already rotated to display orientation. For a
            // portrait video with a 90/270 rotation flag, that means the coded dimensions are
            // sideways relative to the frames we'll actually get — swap them so the target GIF
            // size matches the real (rotated) frame shape instead of squishing it.
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val tmp = w; w = h; h = tmp
            }
            val available = (total - trimStartMs).coerceAtLeast(0)
            Triple(available.coerceAtMost(trimDurationMs.coerceAtMost(MAX_DURATION_MS)), w, h)
        } finally {
            retriever.release()
        }

        var fps = 30
        var width = srcWidth.coerceAtMost(480)
        var bestFile: File? = null
        var bestBytes = Long.MAX_VALUE

        for (attempt in 1..MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            val passBase = (attempt - 1).toFloat() / MAX_ATTEMPTS
            val height = (width.toDouble() / srcWidth * srcHeight).toInt().coerceAtLeast(2).let { it - it % 2 }
            val widthEven = width - width % 2
            onProgress("Rendering GIF: ${widthEven}x$height @ ${fps}fps (attempt $attempt/$MAX_ATTEMPTS)...", passBase)

            val passFile = File(outputFile.parentFile, "gifpass${attempt}_${outputFile.name}")
            renderGif(context, sourceUri, trimStartMs, durationMs, fps, widthEven, height, passFile) { fraction ->
                onProgress(
                    "Rendering GIF: ${widthEven}x$height @ ${fps}fps (attempt $attempt/$MAX_ATTEMPTS)...",
                    passBase + fraction / MAX_ATTEMPTS
                )
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
            if (fps <= MIN_FPS && width <= MIN_WIDTH) {
                onProgress("Reached minimum quality; can't shrink further.", 1f)
                break
            }

            // Alternate shrinking resolution and frame rate; both roughly linearly affect size.
            if (attempt % 2 == 1 && width > MIN_WIDTH) {
                width = (width * 0.75).toInt().coerceAtLeast(MIN_WIDTH)
            } else if (fps > MIN_FPS) {
                fps = (fps * 0.75).toInt().coerceAtLeast(MIN_FPS)
            } else if (width > MIN_WIDTH) {
                width = (width * 0.75).toInt().coerceAtLeast(MIN_WIDTH)
            }
        }

        val result = bestFile ?: throw IllegalStateException("GIF conversion failed to produce output")
        if (result != outputFile) {
            result.copyTo(outputFile, overwrite = true)
            result.delete()
        }
        return outputFile
    }

    private suspend fun renderGif(
        context: Context,
        sourceUri: Uri,
        trimStartMs: Long,
        durationMs: Long,
        fps: Int,
        width: Int,
        height: Int,
        outFile: File,
        onProgress: (Float) -> Unit
    ) {
        if (outFile.exists()) outFile.delete()
        val retriever = MediaMetadataRetriever()
        FileOutputStream(outFile).use { fos ->
            val encoder = GifEncoder(fos, width, height, loopCount = 0)
            encoder.start()
            try {
                retriever.setDataSource(context, sourceUri)
                val frameCount = ((durationMs / 1000.0) * fps).toInt().coerceAtLeast(1)
                val delayCs = (100.0 / fps).toInt().coerceAtLeast(1)
                for (i in 0 until frameCount) {
                    currentCoroutineContext().ensureActive()
                    val timeUs = trimStartMs * 1000L + (i * 1_000_000L / fps)
                    // OPTION_CLOSEST decodes the exact frame at timeUs; OPTION_CLOSEST_SYNC
                    // snaps to the nearest keyframe instead, which with typical ~1-2s keyframe
                    // intervals collapses most requested timestamps onto the same frame and
                    // makes playback look choppy/stuttery.
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val scaled = if (frame.width != width || frame.height != height) {
                            Bitmap.createScaledBitmap(frame, width, height, true)
                        } else frame
                        encoder.writeFrame(scaled, delayCs)
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                    }
                    onProgress((i + 1).toFloat() / frameCount)
                }
            } finally {
                retriever.release()
                encoder.finish()
            }
        }
    }
}
