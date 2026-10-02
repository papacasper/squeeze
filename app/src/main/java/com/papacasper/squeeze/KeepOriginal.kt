package com.papacasper.squeeze

/**
 * Re-encoding a file that already fits can make it bigger (a small, well-compressed clip or a tiny photo).
 * In that case the original is the better result, so it is handed back untouched with a note.
 */
object KeepOriginal {
    const val NOTE = "Kept the original: it already fits and compressing it would not make it smaller."

    /**
     * True when the original already fits [targetBytes] and the result is no smaller. Not for trimmed or split videos
     * or GIF conversions, where the result is a different thing from the original and the sizes can't be compared.
     */
    fun shouldKeep(originalBytes: Long, resultBytes: Long, targetBytes: Long, comparable: Boolean): Boolean =
        comparable && originalBytes in 1..targetBytes && resultBytes >= originalBytes

    fun isKept(settings: String): Boolean = settings == NOTE
}
