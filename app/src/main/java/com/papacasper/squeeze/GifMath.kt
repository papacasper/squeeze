package com.papacasper.squeeze

import kotlin.math.sqrt

/**
 * Retry ladder for GIF output. Frame rate is never touched: size comes down through the
 * colour-distance tolerance for delta frames first (nearly invisible), then resolution.
 */
object GifMath {
    /** Tolerance levels (RGB distance) tried in order; index 0 is exact. */
    val TOLERANCES = intArrayOf(0, 5, 10)

    // Bytes scale roughly with pixel count, i.e. with width squared; aim a little under the target.
    private const val WIDTH_SAFETY = 0.92

    // A pass within this multiple of the target can plausibly be closed by tolerance alone.
    private const val TOLERANCE_ONLY_RANGE = 2.0

    data class Step(val width: Int, val toleranceIndex: Int)

    /** The next attempt after an oversized pass, or null when nothing is left to try. */
    fun nextStep(width: Int, toleranceIndex: Int, passBytes: Long, targetBytes: Long, minWidth: Int): Step? {
        val lastTolerance = TOLERANCES.lastIndex
        if (passBytes <= targetBytes * TOLERANCE_ONLY_RANGE && toleranceIndex < lastTolerance) {
            return Step(width, toleranceIndex + 1)
        }
        val newWidth = shrunkWidth(width, passBytes, targetBytes, minWidth)
        if (newWidth < width) return Step(newWidth, maxOf(toleranceIndex, 1))
        return if (toleranceIndex < lastTolerance) Step(width, toleranceIndex + 1) else null
    }

    /** Width expected to land under the target, always a real reduction while above [minWidth]. */
    fun shrunkWidth(width: Int, passBytes: Long, targetBytes: Long, minWidth: Int): Int {
        if (width <= minWidth) return width
        val ratio = sqrt(targetBytes.toDouble() / passBytes.coerceAtLeast(1L)) * WIDTH_SAFETY
        val stepped = (width * ratio.coerceAtMost(0.95)).toInt().coerceAtLeast(minWidth)
        return stepped - stepped % 2
    }
}
