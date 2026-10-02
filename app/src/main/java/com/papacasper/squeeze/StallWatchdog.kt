package com.papacasper.squeeze

/**
 * Decides when a Transformer export has hung. Some hardware encoders deadlock without ever calling back, either
 * partway through (progress freezes) or before the first frame comes out (progress never starts).
 * Feed it one [tick] per poll; pure so the thresholds can be unit-tested.
 */
class StallWatchdog(
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
    private val freezeTimeoutMs: Long = FREEZE_TIMEOUT_MS
) {
    private var lastProgress = -1
    private var sinceChangeMs = 0L

    /**
     * [progress] is the percentage Transformer reports, or null while it says progress isn't available yet.
     * Returns true once the export has gone quiet for longer than its threshold. A source with no known
     * duration reports "unavailable" for the whole export; don't tick for that state.
     */
    fun tick(progress: Int?, dtMs: Long): Boolean {
        if (progress != null && progress != lastProgress) {
            lastProgress = progress
            sinceChangeMs = 0L
            return false
        }
        sinceChangeMs += dtMs
        val limit = if (lastProgress >= 0) freezeTimeoutMs else startTimeoutMs
        return sinceChangeMs >= limit
    }

    companion object {
        // Opening an 8K source and spinning up both codecs is slow, so the first frame gets longer than a frozen mid-export.
        const val START_TIMEOUT_MS = 60_000L
        const val FREEZE_TIMEOUT_MS = 20_000L
    }
}
