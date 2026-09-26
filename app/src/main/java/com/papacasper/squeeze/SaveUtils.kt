package com.papacasper.squeeze

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

object SaveUtils {

    /** Copies the compressed file into the public Downloads/Squeeze folder and returns its content Uri. */
    fun saveToDownloads(context: Context, file: File, displayName: String, mimeType: String): Uri =
        insertIntoDownloads(context, displayName, mimeType) { out ->
            FileInputStream(file).use { it.copyTo(out) }
        }

    /** Copies an arbitrary content Uri (picked file or downloaded file) into Downloads/Squeeze as-is, no compression. */
    fun saveUriToDownloads(context: Context, source: Uri, displayName: String, mimeType: String): Uri =
        insertIntoDownloads(context, displayName, mimeType) { out ->
            val input = context.contentResolver.openInputStream(source)
                ?: throw IOException("Could not read the source file")
            input.use { it.copyTo(out) }
        }

    // IS_PENDING keeps half-written files hidden from other apps; a failed write deletes the
    // row so we never leave an empty or truncated file behind in Downloads.
    private fun insertIntoDownloads(
        context: Context,
        displayName: String,
        mimeType: String,
        write: (OutputStream) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Squeeze")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")

        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open $displayName for writing")
            out.use(write)
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    fun shareIntent(uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
