package com.papacasper.discordcompressor

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Minimal animated GIF89a writer. Each frame is independently quantized with
 * [MedianCutQuantizer] (adaptive per-frame palette) and compressed with GIF's
 * variable-width LZW coding. Frames are written full-size (no delta/transparency
 * optimization), which keeps this simple at the cost of some file size versus a
 * delta-aware encoder.
 */
class GifEncoder(
    outputStream: OutputStream,
    private val width: Int,
    private val height: Int,
    /** Loop count; 0 = loop forever. */
    private val loopCount: Int = 0
) {
    private val out = BufferedOutputStream(outputStream)
    private var started = false
    private var wroteHeader = false

    fun start() {
        if (started) return
        started = true
    }

    /** Appends one frame. [delayCs] is the frame delay in hundredths of a second. */
    fun writeFrame(bitmap: Bitmap, delayCs: Int) {
        check(started) { "start() not called" }
        val frame = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap

        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)

        // NeuQuant expects a packed RGB byte stream.
        val rgb = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgb[i * 3] = ((p shr 16) and 0xff).toByte()
            rgb[i * 3 + 1] = ((p shr 8) and 0xff).toByte()
            rgb[i * 3 + 2] = (p and 0xff).toByte()
        }

        val quant = MedianCutQuantizer(rgb, 256)
        val colorTab = quant.process()

        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xff
            val g = (p shr 8) and 0xff
            val b = p and 0xff
            indices[i] = quant.map(r, g, b).toByte()
        }

        if (!wroteHeader) {
            writeHeader()
            writeLogicalScreenDescriptor(colorTab)
            writeNetscapeExtension()
            wroteHeader = true
        }

        writeGraphicControlExtension(delayCs)
        writeImageDescriptor()
        writeLocalColorTable(colorTab)
        writeImageData(indices)

        if (frame !== bitmap) frame.recycle()
    }

    fun finish() {
        if (wroteHeader) out.write(0x3b) // trailer
        out.flush()
        out.close()
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    }

    private fun writeLogicalScreenDescriptor(colorTab: ByteArray) {
        writeShort(width)
        writeShort(height)
        // Global color table present, 256 entries (2^(7+1)), color resolution 8 bit.
        out.write(0xf7)
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio
        writePaddedColorTable(colorTab)
    }

    private fun writeNetscapeExtension() {
        out.write(0x21) // extension introducer
        out.write(0xff) // application extension label
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        writeShort(loopCount)
        out.write(0)
    }

    private fun writeGraphicControlExtension(delayCs: Int) {
        out.write(0x21)
        out.write(0xf9)
        out.write(4)
        out.write(0x00) // no transparency, no disposal specified
        writeShort(delayCs)
        out.write(0) // transparent color index (unused)
        out.write(0) // block terminator
    }

    private fun writeImageDescriptor() {
        out.write(0x2c) // image separator
        writeShort(0) // left
        writeShort(0) // top
        writeShort(width)
        writeShort(height)
        out.write(0x87) // local color table present, 256 entries
    }

    private fun writePaddedColorTable(colorTab: ByteArray) {
        out.write(colorTab, 0, colorTab.size)
        val pad = 256 * 3 - colorTab.size
        for (i in 0 until pad) out.write(0)
    }

    private fun writeLocalColorTable(colorTab: ByteArray) {
        writePaddedColorTable(colorTab)
    }

    private fun writeImageData(indices: ByteArray) {
        val minCodeSize = 8
        val encoder = LzwEncoder(width, height, indices, minCodeSize)
        encoder.encode(out)
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }
}

/**
 * GIF-flavored variable-width LZW encoder. Standard algorithm shared by essentially
 * every GIF encoder (derived from the original public-domain "compress" implementation).
 */
private class LzwEncoder(
    private val imgW: Int,
    private val imgH: Int,
    private val pixels: ByteArray,
    colorDepth: Int
) {
    private val initCodeSize = colorDepth.coerceAtLeast(2)
    private var remaining = 0
    private var curPixel = 0

    private val BITS = 12
    private val HSIZE = 5003
    private var n_bits = 0
    private val maxbits = BITS
    private var maxcode = 0
    private val maxmaxcode = 1 shl BITS
    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)
    private var freeEnt = 0
    private var clearFlg = false
    private var gInitBits = 0
    private var clearCode = 0
    private var eofCode = 0
    private var curAccum = 0
    private var curBits = 0
    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F,
        0x00FF, 0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )
    private var aCount = 0
    private val accum = ByteArray(256)

    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        remaining = imgW * imgH
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0) // block terminator
    }

    private fun maxCode(bits: Int) = (1 shl bits) - 1

    private fun compress(initBits: Int, outs: OutputStream) {
        gInitBits = initBits
        clearFlg = false
        n_bits = gInitBits
        maxcode = maxCode(n_bits)
        clearCode = 1 shl (initBits - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2
        aCount = 0

        var ent = nextPixel()

        var hshift = 0
        var fcodeInit = HSIZE
        while (fcodeInit < 65536) {
            hshift++
            fcodeInit *= 2
        }
        hshift = 8 - hshift
        clearHash()

        output(clearCode, outs)

        outer@ while (true) {
            val c = nextPixel()
            if (c == -1) break
            val fcode = (c shl maxbits) + ent
            var i = (c shl hshift) xor ent
            if (htab[i] == fcode) {
                ent = codetab[i]
                continue
            } else if (htab[i] >= 0) {
                val disp = if (i == 0) 1 else HSIZE - i
                while (true) {
                    i -= disp
                    if (i < 0) i += HSIZE
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        continue@outer
                    }
                    if (htab[i] < 0) break
                }
            }
            output(ent, outs)
            ent = c
            if (freeEnt < maxmaxcode) {
                codetab[i] = freeEnt++
                htab[i] = fcode
            } else {
                clearHash()
                freeEnt = clearCode + 2
                clearFlg = true
                output(clearCode, outs)
            }
        }
        output(ent, outs)
        output(eofCode, outs)
    }

    private fun clearHash() {
        for (i in 0 until HSIZE) htab[i] = -1
    }

    private fun output(code: Int, outs: OutputStream) {
        curAccum = curAccum and masks[curBits]
        curAccum = if (curBits > 0) curAccum or (code shl curBits) else code
        curBits += n_bits
        while (curBits >= 8) {
            charOut((curAccum and 0xff).toByte(), outs)
            curAccum = curAccum ushr 8
            curBits -= 8
        }
        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                n_bits = gInitBits
                maxcode = maxCode(n_bits)
                clearFlg = false
            } else {
                n_bits++
                maxcode = if (n_bits == maxbits) maxmaxcode else maxCode(n_bits)
            }
        }
        if (code == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xff).toByte(), outs)
                curAccum = curAccum ushr 8
                curBits -= 8
            }
            flushChar(outs)
        }
    }

    private fun charOut(c: Byte, outs: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar(outs)
    }

    private fun flushChar(outs: OutputStream) {
        if (aCount > 0) {
            outs.write(aCount)
            outs.write(accum, 0, aCount)
            aCount = 0
        }
    }

    private fun nextPixel(): Int {
        if (remaining == 0) return -1
        remaining--
        val pix = pixels[curPixel++]
        return pix.toInt() and 0xff
    }
}
