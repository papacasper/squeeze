package com.papacasper.squeeze

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Wording for why the OS ended the process; pure so it can be unit-tested. */
object ExitMessages {
    fun describe(reason: Int, job: String): String {
        val cause = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                "Android closed Squeeze because the phone ran out of memory."
            ApplicationExitInfo.REASON_CRASH_NATIVE ->
                "Squeeze crashed in native code (usually a video decoder or encoder)."
            ApplicationExitInfo.REASON_CRASH ->
                "Squeeze crashed."
            ApplicationExitInfo.REASON_SIGNALED ->
                "Squeeze was killed by the system, most likely for using too much memory."
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
                "Android stopped Squeeze for using too many resources."
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED ->
                "Squeeze was force-stopped."
            else -> "Squeeze was closed unexpectedly."
        }
        return "$cause It was working on: $job. Very large videos are the usual cause; " +
            "try a shorter or lower-resolution one, or compress it on a PC with squeeze-cli."
    }
}

/**
 * Remembers which job is in flight so that, if the process is killed mid-job (which no in-process
 * handler can observe), the next launch can say what died and why instead of just vanishing.
 */
object ExitDiagnostics {
    private const val PREFS = "diagnostics"
    private const val KEY_REPORTED_AT = "reported_at"
    private const val TAG = "ExitDiagnostics"

    // A file, not SharedPreferences: the job process writes it and the UI process reads it.
    private fun marker(context: Context) = File(context.filesDir, "job.marker")

    fun jobStarted(context: Context, description: String) {
        marker(context).writeText(description)
    }

    fun jobFinished(context: Context) {
        marker(context).delete()
    }

    /**
     * Returns a user-facing message if a job was cut short by the process dying, else null. Clears the
     * marker. [jobProcessAlive] stops a still-running job (UI restarted, job process untouched) from
     * being reported as dead.
     */
    fun takeInterruptedJobMessage(
        context: Context,
        jobProcessAlive: Boolean = CompressionBridge.isJobProcessAlive(context)
    ): String? {
        if (jobProcessAlive) return null
        val file = marker(context)
        val job = runCatching { file.readText() }.getOrNull() ?: return null
        file.delete()

        var reason = -1
        var detail = ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val am = context.getSystemService(ActivityManager::class.java)
            val lastReported = prefs.getLong(KEY_REPORTED_AT, 0L)
            val exits = am?.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                ?.filter { it.timestamp > lastReported }.orEmpty()
            val exit = exits.firstOrNull { it.processName.endsWith(":compress") } ?: exits.firstOrNull()
            if (exit != null) {
                reason = exit.reason
                detail = "reason=${exit.reason} status=${exit.status} pss=${exit.pss}KB rss=${exit.rss}KB ${exit.description.orEmpty()}"
                prefs.edit().putLong(KEY_REPORTED_AT, exit.timestamp).apply()
            }
        }
        Log.w(TAG, "Job process died during '$job': $detail")
        return ExitMessages.describe(reason, job)
    }
}
