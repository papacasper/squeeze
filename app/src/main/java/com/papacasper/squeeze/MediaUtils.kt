package com.papacasper.squeeze

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import java.io.File

fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
}

fun queryVideoDurationMs(context: android.content.Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        retriever.release()
    }
}

/**
 * A frame decoded at thumbnail size. Plain getFrameAtTime returns the full-resolution frame, which for
 * an 8K video is a ~130 MB bitmap per call; that alone is enough to exhaust memory and freeze the app.
 */
fun MediaMetadataRetriever.scaledFrame(timeUs: Long, option: Int, maxDim: Int): Bitmap? {
    var w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    var h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    val rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
    if (w <= 0 || h <= 0) return getFrameAtTime(timeUs, option)?.let { shrinkToFit(it, maxDim) }
    val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
    val dstW = (w * scale).toInt().coerceAtLeast(1)
    val dstH = (h * scale).toInt().coerceAtLeast(1)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        getScaledFrameAtTime(timeUs, option, dstW, dstH)
    } else {
        getFrameAtTime(timeUs, option)?.let { shrinkToFit(it, maxDim) }
    }
}

private fun shrinkToFit(bmp: Bitmap, maxDim: Int): Bitmap {
    val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
    if (scale >= 1f) return bmp
    return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true).also { bmp.recycle() }
}

fun decodeThumbnail(resolver: ContentResolver, uri: Uri, mime: String): Bitmap? {
    return try {
        if (mime.startsWith("video")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(resolver.openFileDescriptor(uri, "r")?.fileDescriptor)
                retriever.scaledFrame(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512)
            } finally {
                retriever.release()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(384, 384), null)
        } else {
            resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }
    } catch (e: Exception) {
        null
    }
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
}

/** "holiday.mov" + result "compressed.mp4" -> "holiday-squeezed.mp4". */
fun squeezedName(originalDisplayName: String?, resultFile: File): String {
    val ext = resultFile.extension.ifEmpty { "bin" }
    val stem = originalDisplayName?.substringBeforeLast('.', "")?.ifBlank { null }
        ?: originalDisplayName?.ifBlank { null }
        ?: "squeeze"
    return "$stem-squeezed.$ext"
}
