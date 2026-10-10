package com.papacasper.squeeze

object DownloadFormat {
    /** Squeeze recompresses everything it downloads, so pulling more than 1080p only costs time and data. */
    const val MAX_HEIGHT = 1080

    /**
     * yt-dlp format selector: best video + best audio no taller than [maxHeight], then a single
     * pre-merged stream under the cap, then whatever exists (e.g. sources that report no height).
     */
    fun selector(maxHeight: Int = MAX_HEIGHT): String =
        "bv*[height<=$maxHeight]+ba/b[height<=$maxHeight]/bv*+ba/b"

    /**
     * Tallest download worth fetching for a [targetBytes] result from a [durationSec] clip: the budget per second
     * decides what height the encoder will end at anyway, so a bigger source only costs download and decode time.
     * Never below [minHeight] (the user's resolution floor); unknown inputs keep [MAX_HEIGHT].
     */
    fun heightFor(targetBytes: Long?, durationSec: Double, minHeight: Int): Int {
        if (targetBytes == null || targetBytes <= 0 || durationSec <= 0) return MAX_HEIGHT
        val videoBps = targetBytes * 8 / durationSec - 128_000
        val height = when {
            videoBps < 700_000 -> 480
            videoBps < 1_800_000 -> 720
            else -> MAX_HEIGHT
        }
        return height.coerceIn(minHeight.coerceAtMost(MAX_HEIGHT), MAX_HEIGHT)
    }

    /**
     * Turns yt-dlp's raw error into something a user can act on. yt-dlp messages start with "ERROR: [site] id:"
     * and name internals; the common cases get a plain sentence, anything else keeps its text minus the prefix.
     */
    fun friendlyError(raw: String?): String {
        val msg = raw.orEmpty().lineSequence().firstOrNull { it.contains("ERROR") }?.trim() ?: raw.orEmpty().trim()
        val lower = msg.lowercase()
        return when {
            "unsupported url" in lower -> "Squeeze can't download from this site. Save the video on your phone, then pick it with Choose file."
            "private" in lower || "login" in lower || "sign in" in lower || "cookies" in lower ->
                "This video needs an account to watch (private, age-restricted or members-only), so Squeeze can't download it."
            "not available" in lower || "unavailable" in lower || "has been removed" in lower || "404" in lower ->
                "That video isn't available anymore, or not in your country."
            "unable to resolve" in lower || "network is unreachable" in lower || "timed out" in lower ->
                "Couldn't reach the site. Check your connection and try again."
            msg.isEmpty() -> "Download failed"
            else -> msg.removePrefix("ERROR:").trim().replace(Regex("^\\[[^]]+] [^:]+: "), "")
        }
    }

    /** Mime type from a downloaded file's name. Unknown extensions are assumed to be video, as before. */
    fun mimeFor(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "m4a", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "opus", "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> java.net.URLConnection.guessContentTypeFromName(fileName) ?: "video/mp4"
        }
    }
}
