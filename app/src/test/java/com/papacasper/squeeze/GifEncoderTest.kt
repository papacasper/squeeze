package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import org.junit.Test

class GifEncoderTest {
    private val w = 64
    private val h = 48

    private fun solid(rgb: Int) = IntArray(w * h) { rgb }

    /** A dark frame with a moving 8x8 red square. */
    private fun squareFrame(x: Int): IntArray = IntArray(w * h) { i ->
        val px = i % w; val py = i / w
        if (px in x until x + 8 && py in 10 until 18) 0xFF0000 else 0x101010
    }

    private fun encode(frames: List<IntArray>, tolerance: Int = 0): ByteArray {
        val palette = GifPalette.build(frames)
        val bytes = ByteArrayOutputStream()
        val enc = GifEncoder(bytes, w, h, palette = palette, tolerance = tolerance)
        enc.start()
        frames.forEach { enc.writePixels(it, 3) }
        enc.finish()
        return bytes.toByteArray()
    }

    /**
     * Minimal GIF89a reader: decodes each frame's LZW data and composites it onto a canvas the way
     * a viewer does (transparent index leaves the previous pixel). Also validates the byte layout.
     */
    private fun composited(gif: ByteArray): List<IntArray> {
        var pos = 0
        fun u8() = gif[pos++].toInt() and 0xff
        fun u16() = u8() or (u8() shl 8)
        assertEquals("GIF89a", String(gif, 0, 6, Charsets.US_ASCII)); pos = 6
        assertEquals(w, u16()); assertEquals(h, u16())
        val flags = u8(); u8(); u8()
        assertTrue("global table expected", flags and 0x80 != 0)
        val global = ByteArray(3 * (1 shl ((flags and 7) + 1))); gif.copyInto(global, 0, pos, pos + global.size); pos += global.size

        val canvas = IntArray(w * h)
        val frames = mutableListOf<IntArray>()
        var transparent = -1
        while (true) {
            when (val block = u8()) {
                0x3b -> return frames
                0x21 -> {
                    val label = u8()
                    if (label == 0xf9) {
                        u8(); val f = u8(); u16(); val t = u8(); u8()
                        transparent = if (f and 1 != 0) t else -1
                    } else {
                        var n = u8()
                        while (n != 0) { pos += n; n = u8() }
                    }
                }
                0x2c -> {
                    val left = u16(); val top = u16(); val fw = u16(); val fh = u16()
                    assertEquals("no local table", 0, u8() and 0x80)
                    val minCode = u8()
                    val data = ByteArrayOutputStream()
                    var n = u8()
                    while (n != 0) { data.write(gif, pos, n); pos += n; n = u8() }
                    val indices = lzwDecode(data.toByteArray(), minCode, fw * fh)
                    for (i in indices.indices) {
                        val idx = indices[i]
                        if (idx == transparent) continue
                        val x = left + i % fw; val y = top + i / fw
                        canvas[y * w + x] = ((global[idx * 3].toInt() and 0xff) shl 16) or
                            ((global[idx * 3 + 1].toInt() and 0xff) shl 8) or (global[idx * 3 + 2].toInt() and 0xff)
                    }
                    frames.add(canvas.copyOf())
                    transparent = -1
                }
                else -> error("unexpected block 0x${block.toString(16)}")
            }
        }
    }

    private fun lzwDecode(data: ByteArray, minCode: Int, count: Int): IntArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = IntArray(4096); val stack = IntArray(4097)
        var codeSize = minCode + 1; var next = eoi + 1; var prev = -1
        var bitBuf = 0; var bits = 0; var p = 0
        val out = IntArray(count); var o = 0
        while (o < count) {
            while (bits < codeSize) { if (p >= data.size) return out; bitBuf = bitBuf or ((data[p++].toInt() and 0xff) shl bits); bits += 8 }
            val code = bitBuf and ((1 shl codeSize) - 1); bitBuf = bitBuf ushr codeSize; bits -= codeSize
            if (code == clear) { codeSize = minCode + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) break
            var sp = 0; var cur = code
            if (prev != -1 && code >= next) { stack[sp++] = 0; cur = prev }   // KwKwK case; first char fixed below
            while (cur >= clear) { stack[sp++] = suffix[cur]; cur = prefix[cur] }
            stack[sp++] = cur
            val first = cur
            if (prev != -1 && code >= next) stack[0] = first
            while (sp > 0 && o < count) out[o++] = stack[--sp]
            if (prev != -1 && next < 4096) { prefix[next] = prev; suffix[next] = first; next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++ }
            prev = code
        }
        return out
    }

    @Test fun deltaFramesReproduceEveryFrame() {
        val frames = (0 until 6).map { squareFrame(it * 6) }
        val decoded = composited(encode(frames))
        assertEquals(frames.size, decoded.size)
        frames.forEachIndexed { i, expected -> assertTrue("frame $i differs", expected.contentEquals(decoded[i])) }
    }

    @Test fun deltaFramesAreMuchSmallerThanFullFrames() {
        val frames = (0 until 12).map { squareFrame(it * 4) }
        val delta = encode(frames).size
        val separate = frames.sumOf { encode(listOf(it)).size }
        assertTrue("delta $delta vs separate $separate", delta * 3 < separate)
    }

    @Test fun identicalFramesKeepTheirTimingAndCostAlmostNothing() {
        val frames = List(5) { solid(0x336699) }
        val gif = encode(frames)
        assertEquals(5, composited(gif).size)
        assertTrue(gif.size < 1400) // dominated by the 768-byte color table
    }

    @Test fun toleranceSuppressesNearlyInvisibleChanges() {
        val a = solid(0x808080)
        val b = solid(0x828282)
        val exact = encode(listOf(a, b), tolerance = 0)
        val lossy = encode(listOf(a, b), tolerance = 5)
        assertTrue(lossy.size < exact.size)
        // Within tolerance the viewer keeps showing the old colour.
        assertTrue(composited(lossy)[1].all { it == composited(lossy)[0][0] })
    }

    @Test fun paletteSamplingCoversTheClip() {
        assertEquals(listOf(0, 1, 2), GifPalette.sampleIndices(3, 12))
        val idx = GifPalette.sampleIndices(100, 10)
        assertEquals(10, idx.size)
        assertEquals(0, idx.first())
        assertTrue(idx.last() >= 90)
    }
}
