package com.papacasper.squeeze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageCompressor {

    /** Binary-searches JPEG quality until the encoded size is <= targetBytes. Returns the output file. */
    fun compress(context: Context, sourceUri: Uri, targetBytes: Long, outputFile: File): File {
        val bitmap = decodeAndOrient(context, sourceUri)

        var lo = 2
        var hi = 95
        var best: ByteArray? = null

        // Quick check: even at lowest quality we might still exceed target (e.g. huge resolution).
        // In that case fall back to downscaling after quality search bottoms out.
        var working = bitmap
        var scaleAttempts = 0

        while (scaleAttempts < 4) {
            lo = 2
            hi = 95
            best = null
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val bytes = encodeJpeg(working, mid)
                if (bytes.size.toLong() <= targetBytes) {
                    best = bytes
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (best != null) break

            // Even quality=2 was too big -> downscale 50% and retry.
            val newWidth = (working.width * 0.7).toInt().coerceAtLeast(1)
            val newHeight = (working.height * 0.7).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(working, newWidth, newHeight, true)
            if (working !== bitmap) working.recycle()
            working = scaled
            scaleAttempts++
        }

        // If we still have nothing, use the smallest we can produce (quality 2 at smallest scale).
        val finalBytes = best ?: encodeJpeg(working, 2)

        FileOutputStream(outputFile).use { it.write(finalBytes) }
        if (working !== bitmap) working.recycle()
        bitmap.recycle()
        return outputFile
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    private fun decodeAndOrient(context: Context, uri: Uri): Bitmap {
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalArgumentException("Could not decode image")

        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }

        return if (matrix.isIdentity) {
            raw
        } else {
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            raw.recycle()
            rotated
        }
    }
}
