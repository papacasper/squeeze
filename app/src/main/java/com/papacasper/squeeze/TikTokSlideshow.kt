package com.papacasper.squeeze

import org.json.JSONArray
import org.json.JSONObject

/**
 * TikTok photo posts have no video stream: yt-dlp only returns their soundtrack (an .m4a/.mp3), which the
 * video pipeline then chokes on. The images and the soundtrack are in the post page's embedded JSON, so
 * this reads them out and [SlideshowVideo] stitches them into an mp4.
 */
object TikTokSlideshow {
    data class Post(val imageUrls: List<String>, val audioUrl: String?, val audioSec: Double)

    fun isTikTokUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase() ?: return false
        return host == "tiktok.com" || host.endsWith(".tiktok.com")
    }

    private val DATA_SCRIPT = Regex(
        """<script[^>]*id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val SIGI_SCRIPT = Regex("""<script[^>]*id="SIGI_STATE"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** The photo post described by a TikTok post page, or null when it isn't one (normal video, unreadable page). */
    fun parse(html: String): Post? {
        val json = (DATA_SCRIPT.find(html) ?: SIGI_SCRIPT.find(html))?.groupValues?.get(1) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val imagePost = findKey(root, "imagePost") as? JSONObject ?: return null
        val images = imagePost.optJSONArray("images") ?: return null
        val urls = (0 until images.length()).mapNotNull { i ->
            images.optJSONObject(i)?.optJSONObject("imageURL")?.optJSONArray("urlList")?.let(::firstHttps)
        }
        if (urls.isEmpty()) return null
        // The post's item holds the music; searching from the imagePost's parent would be tighter, but the
        // page has one item struct and the music block is not nested under anything else with that name.
        val music = findKey(root, "music") as? JSONObject
        val audioUrl = music?.optString("playUrl")?.takeIf { it.startsWith("http") }
        return Post(urls, audioUrl, music?.optDouble("duration", 0.0)?.takeIf { it > 0 } ?: 0.0)
    }

    private fun firstHttps(list: JSONArray): String? =
        (0 until list.length()).map { list.optString(it) }.firstOrNull { it.startsWith("http") }

    private fun findKey(node: Any?, key: String): Any? = when (node) {
        is JSONObject -> {
            if (node.has(key)) node.get(key)
            else node.keys().asSequence().firstNotNullOfOrNull { findKey(node.opt(it), key) }
        }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findKey(node.opt(it), key) }
        else -> null
    }

    /** How long each image shows and how long the video runs. */
    data class Timing(val perImageMs: Long, val totalMs: Long)

    const val MIN_IMAGE_MS = 2_000L
    const val MAX_IMAGE_MS = 6_000L
    const val NO_AUDIO_IMAGE_MS = 3_000L

    /** Spreads the images across the soundtrack, each shown 2–6 s; with no soundtrack, 3 s each. */
    fun timing(imageCount: Int, audioSec: Double): Timing {
        val n = imageCount.coerceAtLeast(1)
        val per = if (audioSec > 0) ((audioSec * 1000) / n).toLong().coerceIn(MIN_IMAGE_MS, MAX_IMAGE_MS) else NO_AUDIO_IMAGE_MS
        return Timing(per, per * n)
    }
}
