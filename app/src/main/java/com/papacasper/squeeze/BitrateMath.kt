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

    /** Everything the user may lower quality down to; the defaults are the app's stock floors. */
    data class Floors(
        val minHeight: Int = 720,
        val minFps: Float = MIN_FPS,
        val minAudioBitrate: Long = AUDIO_STEPS.last()
    ) {
        /** Output heights, tallest first, ending at [minHeight]. */
        fun ladder(): IntArray = (FULL_LADDER.filter { it > minHeight } + minHeight).toIntArray()

        /** Audio bitrates the retries may step down through, highest first, none below [minAudioBitrate]. */
        fun audioSteps(): LongArray = AUDIO_STEPS.filter { it >= minAudioBitrate }.toLongArray()
    }

    private val FULL_LADDER = intArrayOf(1080, 720, 540, 480, 360)

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
    fun nextLowerAudioBitrate(current: Long?, sourceAudioBitrate: Long, floors: Floors = Floors()): Long? {
        if (sourceAudioBitrate <= 0) return null
        val from = current ?: sourceAudioBitrate
        return floors.audioSteps().firstOrNull { it < from }
    }

    /** True when [bitrate] gives frames at [rungHeight] fewer than [MIN_BITS_PER_PIXEL] at the assumed source frame rate. */
    fun starvedAt(bitrate: Long, sourcePixels: Long, sourceHeight: Int, rungHeight: Int): Boolean {
        if (sourcePixels <= 0 || sourceHeight <= 0) return false
        val rungPixels = sourcePixels.toDouble() * rungHeight * rungHeight / (sourceHeight.toDouble() * sourceHeight)
        return rungPixels * ASSUMED_FPS > bitrate / MIN_BITS_PER_PIXEL
    }

    /**
     * What the first pass will use for a target, for the live "about ..." line. [audioBitrate] is null
     * when audio is copied untouched (or absent); [capFps] means frames drop to the fps floor.
     */
    data class Estimate(val height: Int, val capFps: Boolean, val videoBitrate: Long, val audioBitrate: Long?)

    fun estimate(
        targetBytes: Long,
        durationSec: Double,
        sourceAudioBitrate: Long,
        sourcePixels: Long,
        sourceHeight: Int,
        sourceBytes: Long = Long.MAX_VALUE,
        floors: Floors = Floors()
    ): Estimate {
        val dur = durationSec.coerceAtLeast(1.0)
        val audioBps = audioReencodeBitrate(sourceAudioBitrate, targetBytes, dur)
        val videoBps = initialVideoBitrate(targetBytes, dur, (audioBps ?: sourceAudioBitrate) * dur / 8.0, sourceBytes)
        val ladder = floors.ladder()
        val idx = startingLadderIndex(videoBps, sourcePixels, sourceHeight, ladder)
        val rung = ladder.getOrElse(idx) { ladder.last() }
        return Estimate(
            height = if (sourceHeight > 0) rung.coerceAtMost(sourceHeight) else rung,
            capFps = starvedAt(videoBps, sourcePixels, sourceHeight, rung),
            videoBitrate = videoBps,
            audioBitrate = audioBps
        )
    }

    fun describe(e: Estimate, floors: Floors): String = buildString {
        append("${e.height}p")
        append(if (e.capFps) " · ${floors.minFps.toInt()} fps" else " · original frame rate")
        e.audioBitrate?.let { append(" · audio ${it / 1000} kbps") }
        append(" · video ~${e.videoBitrate / 1000} kbps")
    }

    enum class Feasibility { OK, ROUGH, UNREACHABLE }

    /**
     * Pre-encode verdict for [targetBytes]. [minBytes] is the smallest output the floors allow
     * (the encoder's real lowest video bitrate plus the lowest audio step); a target under it can't be met. ROUGH means
     * it can be met but only by starving the video at the lowest allowed resolution.
     */
    data class Assessment(val level: Feasibility, val minBytes: Long, val videoBitrate: Long)

    fun assess(
        targetBytes: Long,
        durationSec: Double,
        sourceAudioBitrate: Long,
        sourcePixels: Long,
        sourceHeight: Int,
        sourceBytes: Long = Long.MAX_VALUE,
        floors: Floors = Floors(),
        minVideoBps: Long = MIN_BITRATE
    ): Assessment {
        val dur = durationSec.coerceAtLeast(1.0)
        val lowestAudio = if (sourceAudioBitrate <= 0) 0L else minOf(sourceAudioBitrate, floors.audioSteps().lastOrNull() ?: floors.minAudioBitrate)
        // minVideoBps is what the encoder really writes at the lowest allowed height (see EncoderFloorStore).
        val minBytes = ((maxOf(MIN_BITRATE, minVideoBps) + lowestAudio) * dur / 8.0).toLong()
        val audioBps = audioReencodeBitrate(sourceAudioBitrate, targetBytes, dur) ?: sourceAudioBitrate
        val videoBps = initialVideoBitrate(targetBytes, dur, audioBps * dur / 8.0, sourceBytes)
        val level = when {
            minBytes > targetBytes * TARGET_FILL -> Feasibility.UNREACHABLE
            // The source's own size is the limit, not the target: nothing to warn about.
            sourceBytes.toDouble() * SOURCE_FILL < targetBytes.toDouble() * TARGET_FILL -> Feasibility.OK
            starvedAt(videoBps, sourcePixels, sourceHeight, floors.minHeight.coerceAtMost(sourceHeight)) -> Feasibility.ROUGH
            else -> Feasibility.OK
        }
        return Assessment(level, minBytes, videoBps)
    }

    /**
     * Bitrate corrected by a short encoded sample: [sampleBytes] came from [sampleSec] seconds at
     * [bitrate] (audio included at [audioBitrate]). Scales so the predicted whole-clip video fits the
     * target's video budget, limited to a 0.3x–1.5x move since one sample is only a guide.
     */
    fun calibratedBitrate(
        bitrate: Long, sampleBytes: Long, sampleSec: Double, audioBitrate: Long, durationSec: Double, targetBytes: Long
    ): Long {
        val sampleVideo = (sampleBytes - audioBitrate * sampleSec / 8.0).coerceAtLeast(sampleBytes * 0.1)
        val predictedVideo = sampleVideo * durationSec / sampleSec
        val budgetVideo = (targetBytes * TARGET_FILL - audioBitrate * durationSec / 8.0).coerceAtLeast(targetBytes * 0.1)
        val ratio = (budgetVideo / predictedVideo).coerceIn(0.3, 1.5)
        return (bitrate * ratio).toLong().coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    /** Predicted whole-clip size from the same sample, for messaging. */
    fun predictedBytes(sampleBytes: Long, sampleSec: Double, audioBitrate: Long, durationSec: Double): Long {
        val sampleVideo = (sampleBytes - audioBitrate * sampleSec / 8.0).coerceAtLeast(sampleBytes * 0.1)
        return (sampleVideo * durationSec / sampleSec + audioBitrate * durationSec / 8.0).toLong()
    }

    const val CALIBRATE_MIN_SEC = 120.0
    const val CALIBRATE_SAMPLE_SEC = 8.0

    const val MAX_PARTS = 8
    const val MIN_PART_SEC = 10.0

    /**
     * Fewest equal-length parts that each fit [targetBytes] without looking rough, or 1 when
     * splitting can't help (already fine, or still not OK at [MAX_PARTS] / [MIN_PART_SEC] parts).
     */
    fun partsNeeded(
        targetBytes: Long,
        durationSec: Double,
        sourceAudioBitrate: Long,
        sourcePixels: Long,
        sourceHeight: Int,
        sourceBytes: Long = Long.MAX_VALUE,
        floors: Floors = Floors(),
        minVideoBps: Long = MIN_BITRATE
    ): Int {
        fun level(n: Int) = assess(
            targetBytes, durationSec / n, sourceAudioBitrate, sourcePixels, sourceHeight,
            if (sourceBytes == Long.MAX_VALUE) sourceBytes else sourceBytes / n, floors, minVideoBps
        ).level
        if (level(1) == Feasibility.OK) return 1
        for (n in 2..MAX_PARTS) {
            if (durationSec / n < MIN_PART_SEC) break
            if (level(n) == Feasibility.OK) return n
        }
        return 1
    }

    /** "clip-squeezed.mp4" -> "clip-squeezed-part2of3.mp4". */
    fun partName(baseName: String, index: Int, count: Int): String {
        val stem = baseName.substringBeforeLast('.', baseName)
        val ext = baseName.substringAfterLast('.', "")
        return "$stem-part${index + 1}of$count" + if (ext.isEmpty()) "" else ".$ext"
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

    /**
     * Audio to try after a pass with the video already at its floor still came out over [goalBytes]: the highest
     * step below [current] (null = copying the source's [sourceAudioBitrate]) that the pass's video size plus that
     * audio is predicted to fit, else the lowest step. Jumping straight there instead of one step per pass saves
     * whole passes on long clips. Null when there is no lower step or no audio.
     */
    fun audioStepAfterOverPass(
        current: Long?,
        sourceAudioBitrate: Long,
        passBytes: Long,
        passAudioBytes: Double,
        goalBytes: Long,
        durationSec: Double,
        floors: Floors = Floors()
    ): Long? {
        if (sourceAudioBitrate <= 0) return null
        val from = current ?: sourceAudioBitrate
        val lower = floors.audioSteps().filter { it < from }
        if (lower.isEmpty()) return null
        val videoBytes = (passBytes - passAudioBytes).coerceAtLeast(0.0)
        return lower.firstOrNull { videoBytes + it * durationSec / 8.0 <= goalBytes } ?: lower.last()
    }

    /**
     * Video bitrate for the pass after an audio step: what the last pass's video really took, scaled to the room
     * left beside the new audio. Never resets to a fresh whole-budget estimate, which ignores that the encoder
     * already overshot (it sent a 720p clip that was over at 100 kbps back up to 194 kbps).
     */
    fun videoBitrateAfterAudioStep(
        bitrate: Long, passBytes: Long, oldAudioBytes: Double, newAudioBytes: Double, goalBytes: Long
    ): Long {
        val passVideo = (passBytes - oldAudioBytes).coerceAtLeast(passBytes * 0.1)
        val room = goalBytes - newAudioBytes
        if (room <= 0 || passVideo <= 0) return MIN_BITRATE
        return (bitrate * (room / passVideo) * 0.9).toLong().coerceIn(MIN_BITRATE, MAX_BITRATE)
    }
}
