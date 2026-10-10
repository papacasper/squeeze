package com.papacasper.squeeze

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.concurrent.thread

/**
 * Keeps the bundled yt-dlp fresh in the background, at most once a week. Sites change their pages often and a
 * stale yt-dlp is the usual reason a link that worked last month fails; updating only after a failure made the
 * user wait through the failure first.
 */
object YtDlpUpdater {
    private const val PREFS = "ytdlp_update"
    private const val KEY_LAST = "last_check_ms"
    const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0 || nowMs - lastCheckMs >= INTERVAL_MS

    fun updateIfDue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!isDue(prefs.getLong(KEY_LAST, 0L), now)) return
        thread(name = "yt-dlp-update", isDaemon = true) {
            // Swapping yt-dlp under a running download could break it; that download retries with an update anyway.
            if (DownloadRepository.state.value is DownloadState.Working) return@thread
            runCatching { YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE) }
                .onSuccess {
                    prefs.edit().putLong(KEY_LAST, now).apply()
                    Log.i("SqueezeUpdate", "yt-dlp update check: $it, now ${YoutubeDL.getInstance().version(context)}")
                }
                .onFailure { Log.w("SqueezeUpdate", "yt-dlp update failed", it) }
        }
    }
}
