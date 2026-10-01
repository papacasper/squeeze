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
