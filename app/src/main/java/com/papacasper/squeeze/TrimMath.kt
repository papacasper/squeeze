package com.papacasper.squeeze

/** Pure trim-window rules shared by the UI state and the compression services. */
object TrimMath {
    const val MIN_CLIP_MS = 500L

    /** True when the window is a real sub-range of the video, not (roughly) all of it. */
    fun isTrimmed(startMs: Long, endMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && endMs > startMs && (startMs > 0L || endMs < durationMs - 250L)

    /** Shortens a window to at most [maxMs] by pulling its end in; a no-op when it already fits. */
    fun capLength(startMs: Long, endMs: Long, maxMs: Long): Pair<Long, Long> =
        if (endMs - startMs > maxMs) startMs to startMs + maxMs else startMs to endMs

    /** Length of the part of the video that will be encoded. */
    fun effectiveDurationMs(startMs: Long, endMs: Long, durationMs: Long): Long =
        if (isTrimmed(startMs, endMs, durationMs)) endMs - startMs else durationMs
}
