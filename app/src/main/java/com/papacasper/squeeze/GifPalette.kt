package com.papacasper.squeeze

/**
 * One shared colour table for a whole GIF, built once from sampled frames ("palettegen") so every
 * frame maps to the same indices — the precondition for delta frames. Index [TRANSPARENT] is
 * reserved, leaving up to 255 real colours.
 */
class GifPalette private constructor(
    /** Padded 256-entry RGB table, ready to write as a GIF color table. */
    val table: ByteArray,
    private val quantizer: MedianCutQuantizer
) {
    fun indexOf(r: Int, g: Int, b: Int): Int = quantizer.map(r, g, b)

    /** True if palette entries [a] and [b] are within [tol2] (squared RGB distance) of each other. */
    fun sameWithin(a: Int, b: Int, tol2: Int): Boolean {
        if (a == b) return true
        if (tol2 <= 0) return false
        val dr = (table[a * 3].toInt() and 0xff) - (table[b * 3].toInt() and 0xff)
        val dg = (table[a * 3 + 1].toInt() and 0xff) - (table[b * 3 + 1].toInt() and 0xff)
        val db = (table[a * 3 + 2].toInt() and 0xff) - (table[b * 3 + 2].toInt() and 0xff)
        return dr * dr + dg * dg + db * db <= tol2
    }

    companion object {
        const val TRANSPARENT = 255

        /** Builds one palette from [frames] (ARGB pixel arrays; alpha ignored). */
        fun build(frames: List<IntArray>): GifPalette {
            require(frames.isNotEmpty()) { "need at least one frame to build a palette" }
            val total = frames.sumOf { it.size }
            val rgb = ByteArray(total * 3)
            var o = 0
            for (frame in frames) for (p in frame) {
                rgb[o++] = (p shr 16).toByte()
                rgb[o++] = (p shr 8).toByte()
                rgb[o++] = p.toByte()
            }
            val quantizer = MedianCutQuantizer(rgb, TRANSPARENT)
            val colors = quantizer.process()
            val table = ByteArray(256 * 3)
            colors.copyInto(table)
            return GifPalette(table, quantizer)
        }

        /** Evenly spaced subset of [count] items out of [total], for sampling frames across a clip. */
        fun sampleIndices(total: Int, count: Int): List<Int> {
            if (total <= count) return (0 until total).toList()
            return (0 until count).map { it * total / count }.distinct()
        }
    }
}
