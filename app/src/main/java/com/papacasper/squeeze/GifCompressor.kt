package com.papacasper.squeeze

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object GifCompressor {

    private const val MAX_ATTEMPTS = 8
    private const val MIN_WIDTH = 120
    private const val PALETTE_SAMPLE_FRAMES = 12

    /**
     * Decodes an existing animated GIF, then re-encodes it with one shared palette and delta
     * frames, shrinking resolution (after a lossy-tolerance step) until the output is at or
     * under targetBytes or the retry budget is exhausted. Every frame and its timing is kept:
     * the frame rate is never reduced to save bytes.
     */
    suspend fun compress(
        context: Context,
        sourceUri: Uri,
        targetBytes: Long,
        outputFile: File,
        onProgress: (String, Float) -> Unit
    ): File {
        onProgress("Decoding GIF...", 0f)
        val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not read GIF")
        val frames = decodeFrames(bytes)
        if (frames.isEmpty()) throw IllegalStateException("GIF has no frames")

        val srcWidth = frames[0].first.width
        val srcHeight = frames[0].first.height

        // Palette is built once, at source resolution, and reused by every retry.
        val palette = GifPalette.build(
            GifPalette.sampleIndices(frames.size, PALETTE_SAMPLE_FRAMES).map { i ->
                val bmp = frames[i].first
                IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            }
        )

        var width = srcWidth
        var toleranceIndex = 0
        var bestFile: File? = null
        var bestBytes = Long.MAX_VALUE

        try {
            for (attempt in 1..MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()
                val passBase = (attempt - 1).toFloat() / MAX_ATTEMPTS
                val height = (width.toDouble() / srcWidth * srcHeight).toInt().coerceAtLeast(2)
                val widthEven = width - width % 2
                val heightEven = height - height % 2
                val label = "Re-encoding GIF: ${widthEven}x$heightEven (attempt $attempt/$MAX_ATTEMPTS)..."
                onProgress(label, passBase)

                val passFile = File(outputFile.parentFile, "gifcpass${attempt}_${outputFile.name}")
                encodePass(frames, palette, GifMath.TOLERANCES[toleranceIndex], widthEven, heightEven, passFile) { fraction ->
                    onProgress(label, passBase + fraction / MAX_ATTEMPTS)
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
        } finally {
            frames.forEach { it.first.recycle() }
        }

        val result = bestFile ?: throw IllegalStateException("GIF compression failed to produce output")
        if (result != outputFile) {
            result.copyTo(outputFile, overwrite = true)
            result.delete()
        }
        return outputFile
    }

    private suspend fun encodePass(
        frames: List<Pair<Bitmap, Int>>,
        palette: GifPalette,
        tolerance: Int,
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
                frames.forEachIndexed { idx, (bmp, delayMs) ->
                    currentCoroutineContext().ensureActive()
                    val scaled = if (bmp.width != width || bmp.height != height) {
                        Bitmap.createScaledBitmap(bmp, width, height, true)
                    } else bmp
                    encoder.writeFrame(scaled, (delayMs / 10).coerceAtLeast(1))
                    if (scaled !== bmp) scaled.recycle()
                    onProgress((idx + 1).toFloat() / frames.size)
                }
            } finally {
                encoder.finish()
            }
        }
    }

    private fun decodeFrames(bytes: ByteArray): List<Pair<Bitmap, Int>> {
        val header = GifHeaderParser().setData(ByteBuffer.wrap(bytes)).parseHeader()
        val decoder = StandardGifDecoder(SimpleBitmapProvider(), header, ByteBuffer.wrap(bytes), 1)
        decoder.advance()
        val frames = mutableListOf<Pair<Bitmap, Int>>()
        val count = header.numFrames.coerceAtLeast(1)
        for (i in 0 until count) {
            val frame = decoder.nextFrame ?: break
            val delayMs = decoder.getDelay(i).let { if (it <= 0) 100 else it }
            frames.add(frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false) to delayMs)
            decoder.advance()
        }
        return frames
    }
}

private class SimpleBitmapProvider : GifDecoder.BitmapProvider {
    override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
        Bitmap.createBitmap(width, height, config)
    override fun release(bitmap: Bitmap) {}
    override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
    override fun release(bytes: ByteArray) {}
    override fun obtainIntArray(size: Int): IntArray = IntArray(size)
    override fun release(array: IntArray) {}
}
