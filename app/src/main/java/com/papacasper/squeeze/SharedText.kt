package com.papacasper.squeeze

object SharedText {
    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

    // Share sheets often wrap the link in prose ("Check this out https://…!"); drop trailing punctuation.
    private const val TRAILING = ".,;:!?)]}"

    /** First http(s) link in [text], or null. */
    fun firstUrl(text: String?): String? =
        text?.let { URL.find(it)?.value?.trimEnd { c -> c in TRAILING } }
}
