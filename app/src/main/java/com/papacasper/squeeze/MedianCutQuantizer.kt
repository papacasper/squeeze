package com.papacasper.squeeze

/**
 * Median-cut color quantizer. Builds a palette of up to [maxColors] colors from an RGB byte
 * stream and maps colors to palette indices via nearest-neighbor search.
 *
 * Unlike NeuQuant's neural-net approach (which diffuses training updates across neighboring
 * palette entries), median-cut splits color space by population without blending across
 * clusters. That matters for GIF source content: cartoons/memes/screenshots typically have a
 * handful of flat, saturated colors, and NeuQuant's neighbor-radius updates blur those distinct
 * clusters into muted averages (measured: source palette avg saturation ~0.24 became ~0.11
 * after NeuQuant). Median-cut keeps each distinct color its own box when there's room, so flat
 * color content round-trips essentially exactly.
 */
class MedianCutQuantizer(pixels: ByteArray, private val maxColors: Int = 256) {

    private class Box(val colors: MutableList<IntArray>) {
        var rMin = 255; var rMax = 0
        var gMin = 255; var gMax = 0
        var bMin = 255; var bMax = 0
        var population = 0L

        init { recompute() }

        fun recompute() {
            rMin = 255; rMax = 0; gMin = 255; gMax = 0; bMin = 255; bMax = 0
            population = 0L
            for (c in colors) {
                val r = c[0]; val g = c[1]; val b = c[2]; val count = c[3]
                if (r < rMin) rMin = r
                if (r > rMax) rMax = r
                if (g < gMin) gMin = g
                if (g > gMax) gMax = g
                if (b < bMin) bMin = b
                if (b > bMax) bMax = b
                population += count
            }
        }

        fun longestDim(): Int {
            val rSpread = rMax - rMin
            val gSpread = gMax - gMin
            val bSpread = bMax - bMin
            return when {
                rSpread >= gSpread && rSpread >= bSpread -> 0
                gSpread >= rSpread && gSpread >= bSpread -> 1
                else -> 2
            }
        }

        fun average(): IntArray {
            var r = 0L; var g = 0L; var b = 0L
            for (c in colors) {
                val count = c[3].toLong()
                r += c[0].toLong() * count
                g += c[1].toLong() * count
                b += c[2].toLong() * count
            }
            val pop = population.coerceAtLeast(1)
            return intArrayOf((r / pop).toInt(), (g / pop).toInt(), (b / pop).toInt())
        }
    }

    // Palette entries as [r, g, b], built by process().
    private var palette: Array<IntArray> = emptyArray()

    fun process(): ByteArray {
        val histogram = HashMap<Int, Int>()
        var i = 0
        while (i + 2 < pixelsLen) {
            val r = pixelsArr[i].toInt() and 0xff
            val g = pixelsArr[i + 1].toInt() and 0xff
            val b = pixelsArr[i + 2].toInt() and 0xff
            val key = (r shl 16) or (g shl 8) or b
            histogram[key] = (histogram[key] ?: 0) + 1
            i += 3
        }

        val colors = histogram.entries.map { (key, count) ->
            intArrayOf((key shr 16) and 0xff, (key shr 8) and 0xff, key and 0xff, count)
        }.toMutableList()

        if (colors.size <= maxColors) {
            palette = colors.map { intArrayOf(it[0], it[1], it[2]) }.toTypedArray()
        } else {
            val boxes = mutableListOf(Box(colors))
            while (boxes.size < maxColors) {
                val splitIdx = boxes.indices
                    .filter { boxes[it].colors.size > 1 }
                    .maxByOrNull { boxes[it].population }
                    ?: break
                val box = boxes[splitIdx]
                val dim = box.longestDim()
                box.colors.sortBy { it[dim] }
                val totalPop = box.population
                var running = 0L
                var splitAt = 1
                for ((idx, c) in box.colors.withIndex()) {
                    running += c[3]
                    if (running >= totalPop / 2) {
                        splitAt = (idx + 1).coerceIn(1, box.colors.size - 1)
                        break
                    }
                }
                val left = Box(box.colors.subList(0, splitAt).toMutableList())
                val right = Box(box.colors.subList(splitAt, box.colors.size).toMutableList())
                boxes[splitIdx] = left
                boxes.add(right)
            }
            palette = boxes.map { it.average() }.toTypedArray()
        }

        val map = ByteArray(palette.size * 3)
        for (idx in palette.indices) {
            map[idx * 3] = palette[idx][0].toByte()
            map[idx * 3 + 1] = palette[idx][1].toByte()
            map[idx * 3 + 2] = palette[idx][2].toByte()
        }
        return map
    }

    /** Nearest palette index for a true (r, g, b) triple. */
    fun map(r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (idx in palette.indices) {
            val p = palette[idx]
            val dr = p[0] - r; val dg = p[1] - g; val db = p[2] - b
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = idx
            }
        }
        return best
    }

    private val pixelsArr = pixels
    private val pixelsLen = pixels.size
}
