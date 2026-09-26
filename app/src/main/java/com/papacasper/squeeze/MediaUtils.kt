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

fun decodeThumbnail(resolver: ContentResolver, uri: Uri, mime: String): Bitmap? {
    return try {
        if (mime.startsWith("video")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(resolver.openFileDescriptor(uri, "r")?.fileDescriptor)
                retriever.getFrameAtTime(0)
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
    return "%.1f %s".format(value, units[unitIndex])
}

/** "holiday.mov" + result "compressed.mp4" -> "holiday-squeezed.mp4". */
fun squeezedName(originalDisplayName: String?, resultFile: File): String {
    val ext = resultFile.extension.ifEmpty { "bin" }
    val stem = originalDisplayName?.substringBeforeLast('.', "")?.ifBlank { null }
        ?: originalDisplayName?.ifBlank { null }
        ?: "squeeze"
    return "$stem-squeezed.$ext"
}
