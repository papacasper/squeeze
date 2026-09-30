package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val ladder = BitrateMath.HEIGHT_LADDER

    @Test
    fun ladder_neverGoesBelow720p() {
        assertEquals(720, ladder.min())
    }

    @Test
    fun startingLadder_starvedBitrateStartsAtFloor() {
        // 8K 16:9 at the 100 kbps floor: 1080p would get ~0.002 bits/pixel.
        val idx = BitrateMath.startingLadderIndex(100_000, 7680L * 4320, 4320, ladder)
        assertEquals(720, ladder[idx])
    }

    @Test
    fun startingLadder_generousBitrateStaysAt1080p() {
        assertEquals(0, BitrateMath.startingLadderIndex(8_000_000, 1920L * 1080, 1080, ladder))
    }

    @Test
    fun startingLadder_bitrateBetweenRungs() {
        // 2 Mbps / 0.04 / 30 fps = ~1.67M px per frame: fits 720p (921,600) but not 1080p (2,073,600).
        val idx = BitrateMath.startingLadderIndex(2_000_000, 1920L * 1080, 1080, ladder)
        assertEquals(720, ladder[idx])
    }

    @Test
    fun startingLadder_portraitRotatedSourceUsesUprightAspect() {
        // Stored 1920x1080 + 90deg rotation: upright height 1920. A 1080-tall upright frame is 607x1080 = 655k px.
        val idx = BitrateMath.startingLadderIndex(1_000_000, 1920L * 1080, 1920, ladder)
        assertEquals(0, idx)
    }

    @Test
    fun startingLadder_unknownDimensionsKeepTopRung() {
        assertEquals(0, BitrateMath.startingLadderIndex(100_000, 0, 0, ladder))
    }

    @Test
    fun starvedAt_8kAtFloorBitrateIsStarvedEvenAt720p() {
        assertTrue(BitrateMath.starvedAt(100_000, 7680L * 4320, 4320, 720))
    }

    @Test
    fun starvedAt_healthyBitrateIsNot() {
        assertFalse(BitrateMath.starvedAt(4_000_000, 1920L * 1080, 1080, 720))
    }

    @Test
    fun starvedAt_unknownDimensionsIsNot() {
        assertFalse(BitrateMath.starvedAt(100_000, 0, 0, 720))
    }

    @Test
    fun minFps_is24() {
        assertEquals(24f, BitrateMath.MIN_FPS)
    }

    @Test
    fun audioReencode_256kbpsOver20MbIn13MinIsCappedNear66kbps() {
        val bps = BitrateMath.audioReencodeBitrate(256_000, 20_000_000, 776.0)!!
        assertTrue("was $bps", bps in 64_000..70_000)
    }

    @Test
    fun audioReencode_smallAudioIsCopied() {
        // 10 s of 256 kbps audio is 320 KB against a 10 MB target.
        assertNull(BitrateMath.audioReencodeBitrate(256_000, 10_000_000, 10.0))
    }

    @Test
    fun audioReencode_neverBelowFloor() {
        val bps = BitrateMath.audioReencodeBitrate(256_000, 1_000_000, 3600.0)!!
        assertEquals(BitrateMath.MIN_AUDIO_BITRATE, bps)
    }

    @Test
    fun audioReencode_neverRaisesBitrate() {
        // Source already at 48 kbps, below the floor: copy rather than "re-encode up" to 64.
        assertNull(BitrateMath.audioReencodeBitrate(48_000, 1_000_000, 3600.0))
    }

    @Test
    fun audioReencode_noAudioTrack() {
        assertNull(BitrateMath.audioReencodeBitrate(0, 20_000_000, 776.0))
    }

    @Test
    fun nextLowerAudio_stepsDownFromCopiedSource() {
        assertEquals(64_000L, BitrateMath.nextLowerAudioBitrate(null, 256_000))
    }

    @Test
    fun nextLowerAudio_walksTheLadderToTheFloor() {
        assertEquals(48_000L, BitrateMath.nextLowerAudioBitrate(64_000, 256_000))
        assertEquals(32_000L, BitrateMath.nextLowerAudioBitrate(48_000, 256_000))
        assertNull(BitrateMath.nextLowerAudioBitrate(32_000, 256_000))
    }

    @Test
    fun nextLowerAudio_sourceAlreadyBelowLadderTopStartsLower() {
        // Copied 48 kbps source: the only useful step is 32.
        assertEquals(32_000L, BitrateMath.nextLowerAudioBitrate(null, 48_000))
    }

    @Test
    fun nextLowerAudio_noAudio() {
        assertNull(BitrateMath.nextLowerAudioBitrate(null, 0))
    }
}
