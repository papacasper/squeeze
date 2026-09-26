package com.papacasper.squeeze

/** Pure bitrate/ladder arithmetic for [VideoCompressor], kept free of Android types so it can be unit-tested. */
object BitrateMath {
    const val MIN_BITRATE = 100_000L
    const val MAX_BITRATE = 20_000_000L

    // The rest of the target covers container overhead.
    private const val TARGET_FILL = 0.92

    // Encoders often clamp the requested bitrate; a pass that shrinks by less than this
    // versus the previous one means the ladder should step down a resolution.
    private const val STALLED_SHRINK_RATIO = 0.9

    // A source already under the target must not be re-encoded bigger; aim a bit below its own size.
    private const val SOURCE_FILL = 0.8

    /**
     * First-pass video bitrate: the target minus the (untouched) audio, at [TARGET_FILL] of the budget.
     * The budget is capped at [SOURCE_FILL] of [sourceBytes] so small sources shrink rather than grow.
     */
    fun initialVideoBitrate(
        targetBytes: Long,
        durationSec: Double,
        audioBytes: Double,
        sourceBytes: Long = Long.MAX_VALUE
    ): Long {
        val budget = minOf(targetBytes.toDouble() * TARGET_FILL, sourceBytes.toDouble() * SOURCE_FILL)
        return ((budget - audioBytes) * 8 / durationSec).toLong().coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    /** Next bitrate after an oversized pass: scale by the video share's miss, with 15% extra headroom. */
    fun nextVideoBitrate(bitrate: Long, passBytes: Long, targetBytes: Long, audioBytes: Double): Long {
        val passVideoBytes = (passBytes - audioBytes).coerceAtLeast(passBytes * 0.1)
        val targetVideoBytes = (targetBytes - audioBytes).coerceAtLeast(targetBytes * 0.1)
        val ratio = if (passBytes > 0) targetVideoBytes / passVideoBytes else 0.5
        return (bitrate * ratio * 0.85).toLong().coerceAtLeast(MIN_BITRATE)
    }

    /** True when [passBytes] barely shrank versus [previousPassBytes] (0 or unknown -> false). */
    fun shrinkStalled(passBytes: Long, previousPassBytes: Long): Boolean {
        if (previousPassBytes <= 0 || previousPassBytes == Long.MAX_VALUE) return false
        return passBytes.toDouble() / previousPassBytes.toDouble() > STALLED_SHRINK_RATIO
    }
}
