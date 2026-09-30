package com.papacasper.squeeze

/**
 * Progress callbacks fire several times a second; each notification update costs a binder call and
 * the shade can't show them that fast anyway. Allows one post per [minIntervalMs], and only if the
 * percent or message actually changed.
 */
class NotificationThrottle(private val minIntervalMs: Long = 1_000L) {
    private var lastPostMs = Long.MIN_VALUE
    private var lastPercent = -1
    private var lastMessage: String? = null

    fun shouldPost(nowMs: Long, percent: Int, message: String): Boolean {
        val first = lastPostMs == Long.MIN_VALUE
        if (!first) {
            if (percent == lastPercent && message == lastMessage) return false
            if (nowMs - lastPostMs < minIntervalMs) return false
        }
        lastPostMs = nowMs
        lastPercent = percent
        lastMessage = message
        return true
    }
}
