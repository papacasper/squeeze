package com.papacasper.squeeze

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Cloud-backed providers (Google Photos and friends) hand out a stream that may need a full download
 * or transcode on first access, and every later seek (thumbnail, filmstrip, decode) can hit that
 * provider again. Files from those are copied once into the app's cache and used from there.
 */
object FileImport {
    private val LOCAL_AUTHORITIES = setOf(
        "media",
        "com.android.providers.media.documents",
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "downloads",
    )
    private const val SPACE_MARGIN = 200L * 1024 * 1024

    fun needsImport(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        val authority = uri.authority ?: return true
        return authority !in LOCAL_AUTHORITIES && !authority.startsWith(context.packageName)
    }

    private fun importsDir(context: Context) = File(context.cacheDir, "imports")

    fun clear(context: Context) {
        importsDir(context).deleteRecursively()
    }

    /** Copies [uri] into the cache and returns the copy; [onProgress] gets 0..1, or -1 when the size is unknown. */
    suspend fun importToCache(context: Context, uri: Uri, onProgress: (Float) -> Unit): Uri {
        clear(context)
        val dir = importsDir(context).apply { mkdirs() }
        val name = (queryDisplayName(context.contentResolver, uri) ?: "import").replace('/', '_')
        val dest = File(dir, name)

        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: -1L
        if (length > 0 && dir.usableSpace < length + SPACE_MARGIN) {
            throw java.io.IOException(
                "Not enough free storage to import this file (needs ${formatSize(length + SPACE_MARGIN)}). " +
                    "Free some space or download it to the phone first."
            )
        }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't open that file.")
            input.use { src ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        onProgress(if (length > 0) (copied.toFloat() / length).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
        return Uri.fromFile(dest)
    }
}
