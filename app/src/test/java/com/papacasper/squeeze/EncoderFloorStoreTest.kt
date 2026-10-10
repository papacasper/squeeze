package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class EncoderFloorStoreTest {

    @Test
    fun clampedPass_setsTheFloor() {
        val m = EncoderFloorStore.observe(emptyMap(), 720, 100_000L, 248_000L)
        assertEquals(248_000L, m[720])
        assertEquals(248_000L, EncoderFloorStore.floorAt(m, 720))
    }

    @Test
    fun honouredPass_leavesUnknownAlone_andLowersAStaleFloor() {
        assertTrue(EncoderFloorStore.observe(emptyMap(), 720, 2_000_000L, 1_900_000L).isEmpty())
        val lowered = EncoderFloorStore.observe(mapOf(720 to 248_000L), 720, 120_000L, 115_000L)
        assertEquals(120_000L, lowered[720])
    }

    @Test
    fun floorAt_neverBelowMinBitrate_andPerHeight() {
        assertEquals(BitrateMath.MIN_BITRATE, EncoderFloorStore.floorAt(emptyMap(), 720))
        assertEquals(BitrateMath.MIN_BITRATE, EncoderFloorStore.floorAt(mapOf(1080 to 390_000L), 720))
    }

    @Test
    fun ignoresNonsense() {
        assertTrue(EncoderFloorStore.observe(emptyMap(), 0, 100_000L, 248_000L).isEmpty())
        assertTrue(EncoderFloorStore.observe(emptyMap(), 720, 100_000L, -5L).isEmpty())
    }

    @Test
    fun roundTripsThroughTheFile() {
        val dir = Files.createTempDirectory("floors").toFile()
        EncoderFloorStore.record(dir, 720, 100_000L, 248_000L)
        EncoderFloorStore.record(dir, 1080, 103_000L, 394_000L)
        assertEquals(mapOf(720 to 248_000L, 1080 to 394_000L), EncoderFloorStore.load(dir))
        dir.deleteRecursively()
    }
}
