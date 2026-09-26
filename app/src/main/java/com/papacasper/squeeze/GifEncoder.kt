package com.papacasper.squeeze

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Animated GIF89a writer. All frames share one global [GifPalette] (built once up front from
 * sampled frames), and every frame after the first stores only the bounding box of pixels that
 * changed: unchanged pixels are written as a transparent index over the previous frame
 * (disposal 1), which LZW squeezes to almost nothing. A non-zero [tolerance] additionally treats
 * pixels within that colour distance of what's already on screen as unchanged, trading a little
 * fidelity for size without touching frame rate.
 */
class GifEncoder(
    outputStream: OutputStream,
    private val width: Int,
    private val height: Int,
    /** Loop count; 0 = loop forever. */
    private val loopCount: Int = 0,
    private val palette: GifPalette,
    /** Max RGB distance at which a pixel counts as unchanged; 0 = exact (lossless vs. the palette). */
    private val tolerance: Int = 0
) {
    private val out = BufferedOutputStream(outputStream)
    private var started = false
    private var wroteHeader = false
    private var previous: ByteArray? = null

    fun start() {
        if (started) return
        started = true
    }

    /** Appends one frame. [delayCs] is the frame delay in hundredths of a second. */
    fun writeFrame(bitmap: Bitmap, delayCs: Int) {
        val frame = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)
        if (frame !== bitmap) frame.recycle()
        writePixels(pixels, delayCs)
    }

    /** Appends one frame given as [width]x[height] ARGB pixels (alpha ignored). */
    fun writePixels(argb: IntArray, delayCs: Int) {
        check(started) { "start() not called" }
        require(argb.size == width * height) { "expected ${width * height} pixels, got ${argb.size}" }

        val current = ByteArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            current[i] = palette.indexOf((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff).toByte()
        }

        val prev = previous
        if (prev == null) {
            writeHeader()
            writeLogicalScreenDescriptor()
            writeNetscapeExtension()
            wroteHeader = true
            writeGraphicControlExtension(delayCs, transparent = false)
            writeImageDescriptor(0, 0, width, height)
            writeImageData(current, width, height)
            previous = current
            return
        }

        val tol2 = tolerance * tolerance
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                if (!palette.sameWithin(prev[i].toInt() and 0xff, current[i].toInt() and 0xff, tol2)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        writeGraphicControlExtension(delayCs, transparent = true)
        if (maxX < 0) {
            // Nothing visibly changed: a 1x1 transparent frame just carries the delay.
            writeImageDescriptor(0, 0, 1, 1)
            writeImageData(byteArrayOf(GifPalette.TRANSPARENT.toByte()), 1, 1)
            return
        }

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val sub = ByteArray(bw * bh)
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val i = (minY + y) * width + (minX + x)
                if (palette.sameWithin(prev[i].toInt() and 0xff, current[i].toInt() and 0xff, tol2)) {
                    sub[y * bw + x] = GifPalette.TRANSPARENT.toByte()
                } else {
                    sub[y * bw + x] = current[i]
                    prev[i] = current[i]
                }
            }
        }
        writeImageDescriptor(minX, minY, bw, bh)
        writeImageData(sub, bw, bh)
    }

    fun finish() {
        if (wroteHeader) out.write(0x3b) // trailer
        out.flush()
        out.close()
    }

    private fun writeHeader() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    }

    private fun writeLogicalScreenDescriptor() {
        writeShort(width)
        writeShort(height)
        // Global color table present, 256 entries (2^(7+1)), color resolution 8 bit.
        out.write(0xf7)
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio
        out.write(palette.table, 0, palette.table.size)
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

    private fun writeGraphicControlExtension(delayCs: Int, transparent: Boolean) {
        out.write(0x21)
        out.write(0xf9)
        out.write(4)
        // Disposal 1 (leave in place) so later frames can be deltas over this one.
        out.write(0x04 or if (transparent) 0x01 else 0x00)
        writeShort(delayCs)
        out.write(GifPalette.TRANSPARENT)
        out.write(0) // block terminator
    }

    private fun writeImageDescriptor(left: Int, top: Int, w: Int, h: Int) {
        out.write(0x2c) // image separator
        writeShort(left)
        writeShort(top)
        writeShort(w)
        writeShort(h)
        out.write(0x00) // no local color table; the global one applies
    }

    private fun writeImageData(indices: ByteArray, w: Int, h: Int) {
        LzwEncoder(w, h, indices, 8).encode(out)
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
