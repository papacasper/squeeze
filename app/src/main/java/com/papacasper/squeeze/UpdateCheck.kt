package com.papacasper.squeeze

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tells the user when a newer Squeeze is out. Squeeze is sideloaded (no store to update it), so without this
 * people sit on old builds. Asks GitHub for the latest release at most once a day; never downloads anything.
 */
object UpdateCheck {
    private const val LATEST = "https://api.github.com/repos/papacasper/squeeze/releases/latest"
    const val RELEASES_PAGE = "https://github.com/papacasper/squeeze/releases/latest"
    private const val PREFS = "update_check"
    private const val KEY_LAST = "last_check_ms"
    private const val KEY_LATEST = "latest_version"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    // Versions are dates with an optional same-day letter (2026.10.09b), so string order is release order.
    private val VERSION = Regex("""\d{4}\.\d{2}\.\d{2}[a-z]?""")

    /** [latestTag] ("v2026.10.09b") if it is a newer release than [current], else null. */
    fun newer(current: String, latestTag: String?): String? {
        val latest = latestTag?.trim()?.removePrefix("v") ?: return null
        if (!VERSION.matches(latest) || !VERSION.matches(current)) return null
        return latest.takeIf { it > current }
    }

    /** Newer version than the installed one, checking GitHub if the cached answer is a day old. Call off the main thread. */
    fun availableUpdate(context: Context): String? {
        val current = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) >= INTERVAL_MS) {
            fetchLatestTag()?.let { prefs.edit().putString(KEY_LATEST, it).putLong(KEY_LAST, now).apply() }
        }
        return newer(current, prefs.getString(KEY_LATEST, null))
    }

    private fun fetchLatestTag(): String? = runCatching {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("tag_name").ifEmpty { null }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
