package com.papacasper.squeeze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object VideoToGifConverter {

    private const val MAX_ATTEMPTS = 8
    private const val MIN_WIDTH = 160
    private const val START_WIDTH = 480
    // Fixed for every pass: size is recovered through palette/delta/resolution, never by dropping frames.
    private const val FPS = 30
    private const val PALETTE_SAMPLE_FRAMES = 12
    private const val FRAME_CACHE_QUALITY = 95
    // Share of the progress bar taken by the one-time frame extraction.
    private const val EXTRACT_SHARE = 0.25f
    // GIFs get huge fast with length; cap the clip so output stays usable. Longer source
    // videos are trimmed via [trimStartMs] rather than converted in full.
    const val MAX_DURATION_MS = 30_000L

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

        val width0 = (srcWidth.coerceAtMost(START_WIDTH)).let { it - it % 2 }
        val height0 = heightFor(width0, srcWidth, srcHeight)

        // Frames are decoded from the video exactly once, cached as JPEGs, and reused by every
        // retry; only scaling and encoding repeat. The palette is likewise built once.
        val cacheDir = File(outputFile.parentFile, "gifframes").apply { deleteRecursively(); mkdirs() }
        try {
            val frameFiles = extractFrames(context, sourceUri, trimStartMs, durationMs, width0, height0, cacheDir) {
                onProgress("Reading video frames...", it * EXTRACT_SHARE)
            }
            if (frameFiles.isEmpty()) throw IllegalStateException("Couldn't read any frames from the video")
            val palette = buildPalette(frameFiles)
            val delayCs = (100.0 / FPS).roundToInt().coerceAtLeast(2)

            var width = width0
            var toleranceIndex = 0
            var bestFile: File? = null
            var bestBytes = Long.MAX_VALUE

            for (attempt in 1..MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()
                val passBase = EXTRACT_SHARE + (1f - EXTRACT_SHARE) * (attempt - 1) / MAX_ATTEMPTS
                val height = heightFor(width, srcWidth, srcHeight)
                val label = "Rendering GIF: ${width}x$height @ ${FPS}fps (attempt $attempt/$MAX_ATTEMPTS)..."
                onProgress(label, passBase)

                val passFile = File(outputFile.parentFile, "gifpass${attempt}_${outputFile.name}")
                renderGif(frameFiles, palette, GifMath.TOLERANCES[toleranceIndex], delayCs, width, height, passFile) { fraction ->
                    onProgress(label, passBase + (1f - EXTRACT_SHARE) * fraction / MAX_ATTEMPTS)
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
                val next = GifMath.nextStep(width, toleranceIndex, passBytes, targetBytes, MIN_WIDTH)
                if (next == null) {
                    onProgress("Reached minimum quality; can't shrink further.", 1f)
                    break
                }
                width = next.width
                toleranceIndex = next.toleranceIndex
            }

            val result = bestFile ?: throw IllegalStateException("GIF conversion failed to produce output")
            if (result != outputFile) {
                result.copyTo(outputFile, overwrite = true)
                result.delete()
            }
            return outputFile
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun heightFor(width: Int, srcWidth: Int, srcHeight: Int): Int =
        (width.toDouble() / srcWidth * srcHeight).toInt().coerceAtLeast(2).let { it - it % 2 }

    private suspend fun extractFrames(
        context: Context,
        sourceUri: Uri,
        trimStartMs: Long,
        durationMs: Long,
        width: Int,
        height: Int,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): List<File> {
        val retriever = MediaMetadataRetriever()
        val files = mutableListOf<File>()
        try {
            retriever.setDataSource(context, sourceUri)
            val frameCount = ((durationMs / 1000.0) * FPS).toInt().coerceAtLeast(1)
            for (i in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val timeUs = trimStartMs * 1000L + (i * 1_000_000L / FPS)
                // OPTION_CLOSEST decodes the exact frame at timeUs; OPTION_CLOSEST_SYNC
                // snaps to the nearest keyframe instead, which with typical ~1-2s keyframe
                // intervals collapses most requested timestamps onto the same frame and
                // makes playback look choppy/stuttery.
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val scaled = if (frame.width != width || frame.height != height) {
                        Bitmap.createScaledBitmap(frame, width, height, true)
                    } else frame
                    val file = File(cacheDir, "f%05d.jpg".format(i))
                    file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_CACHE_QUALITY, it) }
                    files.add(file)
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()
                }
                onProgress((i + 1).toFloat() / frameCount)
            }
        } finally {
            retriever.release()
        }
        return files
    }

    private fun buildPalette(frameFiles: List<File>): GifPalette {
        val samples = GifPalette.sampleIndices(frameFiles.size, PALETTE_SAMPLE_FRAMES).mapNotNull { i ->
            BitmapFactory.decodeFile(frameFiles[i].absolutePath)?.let { bmp ->
                IntArray(bmp.width * bmp.height).also {
                    bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp.recycle()
                }
            }
        }
        return GifPalette.build(samples)
    }

    private suspend fun renderGif(
        frameFiles: List<File>,
        palette: GifPalette,
        tolerance: Int,
        delayCs: Int,
        width: Int,
        height: Int,
        outFile: File,
        onProgress: (Float) -> Unit
    ) {
        if (outFile.exists()) outFile.delete()
        FileOutputStream(outFile).use { fos ->
            val encoder = GifEncoder(fos, width, height, loopCount = 0, palette = palette, tolerance = tolerance)
            encoder.start()
            try {
                frameFiles.forEachIndexed { i, file ->
                    currentCoroutineContext().ensureActive()
                    val frame = BitmapFactory.decodeFile(file.absolutePath)
                    if (frame != null) {
                        encoder.writeFrame(frame, delayCs)  // scales to width x height if needed
                        frame.recycle()
                    }
                    onProgress((i + 1).toFloat() / frameFiles.size)
                }
            } finally {
                encoder.finish()
            }
        }
    }
}
