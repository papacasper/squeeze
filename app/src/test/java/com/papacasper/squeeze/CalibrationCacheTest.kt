package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalibrationCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = CalibrationCache.Key("content://x/1", 9_000_000_000L, 0, 0, 720, 24f, 8.0)
    private val entry = CalibrationCache.Entry(1_200_000, 900_000, 64_000)

    @Test
    fun missThenHit() {
        val dir = tmp.newFolder()
        assertNull(CalibrationCache.get(dir, key))
        CalibrationCache.put(dir, key, entry)
        assertEquals(entry, CalibrationCache.get(dir, key))
    }

    @Test
    fun differentSettingsDontShareAnEntry() {
        val dir = tmp.newFolder()
        CalibrationCache.put(dir, key, entry)
        assertNull(CalibrationCache.get(dir, key.copy(height = 1080)))
        assertNull(CalibrationCache.get(dir, key.copy(fps = null)))
        assertNull(CalibrationCache.get(dir, key.copy(sourceBytes = 1)))
        assertNull(CalibrationCache.get(dir, key.copy(trimStartMs = 5_000)))
    }

    @Test
    fun putReplacesTheSameKey() {
        val dir = tmp.newFolder()
        CalibrationCache.put(dir, key, entry)
        CalibrationCache.put(dir, key, entry.copy(sampleBytes = 1))
        assertEquals(1L, CalibrationCache.get(dir, key)?.sampleBytes)
    }

    @Test
    fun oldestEntriesAreDroppedPastTheLimit() {
        val dir = tmp.newFolder()
        for (i in 0 until 30) CalibrationCache.put(dir, key.copy(source = "s$i"), entry)
        assertNull(CalibrationCache.get(dir, key.copy(source = "s0")))
        assertEquals(entry, CalibrationCache.get(dir, key.copy(source = "s29")))
    }

    @Test
    fun corruptFileIsTreatedAsEmpty() {
        val dir = tmp.newFolder()
        java.io.File(dir, "calibration.json").writeText("{{ nope")
        assertNull(CalibrationCache.get(dir, key))
        CalibrationCache.put(dir, key, entry)
        assertEquals(entry, CalibrationCache.get(dir, key))
    }
}
