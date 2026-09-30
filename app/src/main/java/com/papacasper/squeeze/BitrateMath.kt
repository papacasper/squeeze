package com.papacasper.squeeze

/** Pure bitrate/ladder arithmetic for [VideoCompressor], kept free of Android types so it can be unit-tested. */
object BitrateMath {
    const val MIN_BITRATE = 100_000L
    const val MAX_BITRATE = 20_000_000L

    /** Lowest bitrate audio is first re-encoded to; fine for speech, passable for music. */
    const val MIN_AUDIO_BITRATE = 64_000L

    /** Bitrates audio steps down through on retries once video is at its floor, highest first. */
    val AUDIO_STEPS = longArrayOf(64_000L, 48_000L, 32_000L)

    // Audio may take at most this share of the size budget before it gets re-encoded.
    private const val MAX_AUDIO_SHARE = 0.35

    /** Lowest frame rate a starved encode may be capped to. */
    const val MIN_FPS = 24f

    /** Output heights, tallest first. 720p is the floor: a target it can't reach is reported, not met by going smaller. */
    val HEIGHT_LADDER = intArrayOf(1080, 720)

    // The rest of the target covers container overhead.
    private const val TARGET_FILL = 0.92

    // Encoders often clamp the requested bitrate; a pass that shrinks by less than this
    // versus the previous one means the ladder should step down a resolution.
    private const val STALLED_SHRINK_RATIO = 0.9

    // Below roughly this many bits per pixel per frame, encoders clamp the request to their own floor.
    private const val MIN_BITS_PER_PIXEL = 0.04
    private const val ASSUMED_FPS = 30.0

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

    /**
     * Index into [ladder] (tallest first) of the first rung whose frames still get at least
     * [MIN_BITS_PER_PIXEL] at [bitrate], so a starving budget begins at a resolution the encoder can
     * actually honour instead of burning passes at one it clamps. [sourcePixels] is the frame area
     * and [sourceHeight] the upright height, which together give the aspect ratio.
     */
    fun startingLadderIndex(bitrate: Long, sourcePixels: Long, sourceHeight: Int, ladder: IntArray): Int {
        if (sourcePixels <= 0 || sourceHeight <= 0) return 0
        val pixelsPerSecondBudget = bitrate / MIN_BITS_PER_PIXEL
        val index = ladder.indexOfFirst { h ->
            val rungPixels = sourcePixels.toDouble() * h * h / (sourceHeight.toDouble() * sourceHeight)
            rungPixels * ASSUMED_FPS <= pixelsPerSecondBudget
        }
        return if (index < 0) ladder.lastIndex else index
    }

    /**
     * Bitrate to re-encode audio to, or null to copy it untouched. Audio is copied while it fits in
     * [MAX_AUDIO_SHARE] of the (overhead-adjusted) budget; otherwise it is capped to that share, never
     * below [MIN_AUDIO_BITRATE] and never above the source's own bitrate. A source with no audio (0) is null.
     */
    fun audioReencodeBitrate(sourceAudioBitrate: Long, targetBytes: Long, durationSec: Double): Long? {
        if (sourceAudioBitrate <= 0) return null
        val shareBps = (targetBytes * TARGET_FILL * MAX_AUDIO_SHARE * 8 / durationSec).toLong()
        if (sourceAudioBitrate <= shareBps) return null
        val capped = shareBps.coerceAtLeast(MIN_AUDIO_BITRATE)
        return if (capped >= sourceAudioBitrate) null else capped
    }

    /**
     * Next audio bitrate down the [AUDIO_STEPS] ladder below [current] (null = audio is being copied at
     * [sourceAudioBitrate]), or null when there is nothing lower to try or no audio at all.
     */
    fun nextLowerAudioBitrate(current: Long?, sourceAudioBitrate: Long): Long? {
        if (sourceAudioBitrate <= 0) return null
        val from = current ?: sourceAudioBitrate
        return AUDIO_STEPS.firstOrNull { it < from }
    }

    /** True when [bitrate] gives frames at [rungHeight] fewer than [MIN_BITS_PER_PIXEL] at the assumed source frame rate. */
    fun starvedAt(bitrate: Long, sourcePixels: Long, sourceHeight: Int, rungHeight: Int): Boolean {
        if (sourcePixels <= 0 || sourceHeight <= 0) return false
        val rungPixels = sourcePixels.toDouble() * rungHeight * rungHeight / (sourceHeight.toDouble() * sourceHeight)
        return rungPixels * ASSUMED_FPS > bitrate / MIN_BITS_PER_PIXEL
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
