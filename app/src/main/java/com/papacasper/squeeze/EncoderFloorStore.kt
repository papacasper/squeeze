package com.papacasper.squeeze

import org.json.JSONObject
import java.io.File

/**
 * What this phone's video encoder really writes when asked for a low bitrate, per output height. Hardware
 * encoders clamp requests below a device-specific floor (an SM-S948U writes ~250 kbps at 720p however low the
 * request), so [BitrateMath.MIN_BITRATE] alone over-promises. Learned from finished passes in the compress
 * process and read by the UI for the feasibility check, so it lives in a file rather than SharedPreferences
 * (which caches per process).
 */
object EncoderFloorStore {
    // A pass whose real video bitrate beats the request by this much was clamped.
    private const val CLAMP_RATIO = 1.3

    private fun file(dir: File) = File(dir, "encoder_floors.json")

    /** Learned floor (video bits per second) by output height. Empty when nothing is known yet. */
    fun load(dir: File): Map<Int, Long> = runCatching {
        val o = JSONObject(file(dir).readText())
        o.keys().asSequence().associate { it.toInt() to o.getLong(it) }
    }.getOrDefault(emptyMap())

    fun record(dir: File, height: Int, requestedBps: Long, actualVideoBps: Long) {
        val all = load(dir)
        val updated = observe(all, height, requestedBps, actualVideoBps)
        if (updated == all) return
        runCatching {
            file(dir).writeText(JSONObject().apply { updated.forEach { (h, bps) -> put(h.toString(), bps) } }.toString())
        }
    }

    /**
     * Folds one finished pass into [known]. A clamped pass (real bitrate well over the request) sets the floor
     * for [height] to what it really wrote; an honoured request below the known floor lowers it.
     */
    fun observe(known: Map<Int, Long>, height: Int, requestedBps: Long, actualVideoBps: Long): Map<Int, Long> {
        if (height <= 0 || requestedBps <= 0 || actualVideoBps <= 0) return known
        val current = known[height]
        return when {
            actualVideoBps > requestedBps * CLAMP_RATIO -> known + (height to actualVideoBps)
            current != null && current > requestedBps * CLAMP_RATIO -> known + (height to requestedBps)
            else -> known
        }
    }

    /** Lowest video bitrate the encoder really writes at [height]: the learned floor, or [BitrateMath.MIN_BITRATE]. */
    fun floorAt(known: Map<Int, Long>, height: Int): Long =
        maxOf(BitrateMath.MIN_BITRATE, known[height] ?: 0L)
}
