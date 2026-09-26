package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BitrateMathTest {

    @Test
    fun initialBitrate_reservesAudioAndFill() {
        // 20MB, 60s, 128kbps audio = 960_000 bytes
        val audio = 128_000 * 60 / 8.0
        val expected = ((20L * 1024 * 1024 * 0.92 - audio) * 8 / 60).toLong()
        assertEquals(expected, BitrateMath.initialVideoBitrate(20L * 1024 * 1024, 60.0, audio))
    }

    @Test
    fun initialBitrate_clamps() {
        assertEquals(BitrateMath.MIN_BITRATE, BitrateMath.initialVideoBitrate(1_000, 600.0, 500.0))
        assertEquals(BitrateMath.MAX_BITRATE, BitrateMath.initialVideoBitrate(2L * 1024 * 1024 * 1024, 1.0, 0.0))
    }

    @Test
    fun nextBitrate_shrinksWhenOversizedAndNeverBelowMin() {
        val lowered = BitrateMath.nextVideoBitrate(1_000_000, 30_000_000, 20_000_000, 1_000_000.0)
        assertTrue(lowered < 1_000_000)
        assertEquals(BitrateMath.MIN_BITRATE, BitrateMath.nextVideoBitrate(BitrateMath.MIN_BITRATE, 1_000_000_000, 1_000, 0.0))
        assertTrue(BitrateMath.nextVideoBitrate(500_000, 0, 1_000, 0.0) >= BitrateMath.MIN_BITRATE)
    }

    @Test
    fun shrinkStalled() {
        assertFalse(BitrateMath.shrinkStalled(50, Long.MAX_VALUE))
        assertFalse(BitrateMath.shrinkStalled(50, 0))
        assertFalse(BitrateMath.shrinkStalled(50, 100))
        assertTrue(BitrateMath.shrinkStalled(95, 100))
    }

    @Test
    fun formatSize_ignoresDeviceLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("20.0 MB", formatSize(20L * 1024 * 1024))
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun initialBitrateNeverAimsAboveSmallSource() {
        // 770 KB source, 10 s, no audio, 10 MB target: budget is 80% of the source, not 92% of the target.
        val bitrate = BitrateMath.initialVideoBitrate(10_000_000, 10.0, 0.0, sourceBytes = 770_000)
        assertEquals((770_000 * 0.8 * 8 / 10).toLong(), bitrate)
    }
}
