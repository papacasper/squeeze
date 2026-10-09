package com.papacasper.squeeze

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Network side of TikTok photo posts: fetch the post page, then its images and soundtrack. */
object SlideshowDownload {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val MAX_PAGE_BYTES = 8 * 1024 * 1024

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true  // short links (vm.tiktok.com) redirect to the post
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", "https://www.tiktok.com/")
        }

    /**
     * The photo post behind [url], or null when it isn't one or the page can't be read (blocked, offline, changed).
     * TikTok serves a stripped page with no post data at /photo/ URLs, but the same post under /video/ has it all,
     * so a redirect-resolved /photo/ URL is retried as /video/.
     */
    fun fetchPost(url: String): TikTokSlideshow.Post? = try {
        val (finalUrl, firstPage) = fetchPage(url)
        TikTokSlideshow.parse(firstPage) ?: TikTokSlideshow.asVideoUrl(finalUrl)?.let { TikTokSlideshow.parse(fetchPage(it).second) }
    } catch (e: java.io.IOException) {
        null
    }

    /** The URL after redirects and the page body; an empty body for a non-200 answer. */
    private fun fetchPage(url: String): Pair<String, String> {
        val conn = open(url).apply { setRequestProperty("Accept", "text/html,application/xhtml+xml") }
        try {
            val finalUrl = conn.url.toString()
            if (conn.responseCode != 200) return finalUrl to ""
            return finalUrl to conn.inputStream.use { it.readAtMost(MAX_PAGE_BYTES).toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun downloadTo(url: String, dest: File) {
        currentCoroutineContext().ensureActive()
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} fetching a slideshow file")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    /** Up to [max] bytes of the stream (InputStream.readNBytes needs API 33; minSdk is 29). */
    private fun InputStream.readAtMost(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
