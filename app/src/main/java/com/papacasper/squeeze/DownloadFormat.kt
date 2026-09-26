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
}
