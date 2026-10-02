package com.papacasper.squeeze

/** One third-party component shown on the About screen. */
data class License(val name: String, val purpose: String, val license: String, val url: String)

object Licenses {
    const val SOURCE_URL = "https://github.com/papacasper/squeeze"
    const val APP_LICENSE = "GPL-3.0-or-later"

    val components = listOf(
        License("AndroidX Media3", "Video transcoding and effects", "Apache-2.0", "https://github.com/androidx/media"),
        License("FFmpeg", "Merging and converting downloaded media", "GPL-3.0 (the bundled build)", "https://ffmpeg.org"),
        License("yt-dlp", "Fetching videos from a pasted URL", "Unlicense", "https://github.com/yt-dlp/yt-dlp"),
        License("youtubedl-android", "Runs yt-dlp and FFmpeg inside the app", "GPL-3.0", "https://github.com/JunkFood02/youtubedl-android"),
        License("Python (Chaquopy runtime)", "Runs yt-dlp", "PSF License", "https://chaquo.com/chaquopy"),
        License("Glide GIF decoder", "Reading existing GIFs", "Apache-2.0 / BSD", "https://github.com/bumptech/glide"),
        License("AndroidX, Jetpack Compose, Kotlin coroutines", "App framework", "Apache-2.0", "https://developer.android.com/jetpack"),
    )

    /** Plain-text version, for sharing or the README. */
    fun asText(): String = buildString {
        appendLine("Squeeze is free software under the $APP_LICENSE license. Source: $SOURCE_URL")
        appendLine()
        components.forEach { appendLine("${it.name} — ${it.purpose}. ${it.license}. ${it.url}") }
    }.trimEnd()
}
