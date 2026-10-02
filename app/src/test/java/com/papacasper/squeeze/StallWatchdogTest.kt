package com.papacasper.squeeze

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallWatchdogTest {
    private fun StallWatchdog.run(progress: Int?, ms: Long, step: Long = 300): Boolean {
        var t = 0L
        while (t < ms) { if (tick(progress, step)) return true; t += step }
        return false
    }

    @Test
    fun steadyProgressNeverStalls() {
        val w = StallWatchdog()
        for (p in 0..100) assertFalse(w.tick(p, 5_000))
    }

    @Test
    fun frozenProgressStallsAfterFreezeTimeout() {
        val w = StallWatchdog(startTimeoutMs = 60_000, freezeTimeoutMs = 20_000)
        assertFalse(w.tick(10, 300))
        assertFalse(w.run(10, 19_000))
        assertTrue(w.run(10, 2_000))
    }

    @Test
    fun neverStartingStallsAfterTheLongerStartTimeout() {
        val w = StallWatchdog(startTimeoutMs = 60_000, freezeTimeoutMs = 20_000)
        assertFalse(w.run(null, 59_000))
        assertTrue(w.run(null, 2_000))
    }

    @Test
    fun startTimeoutIsLongerThanFreezeTimeout() {
        // 30 s without a first frame is fine (8K sources open slowly) but 30 s frozen mid-export is not.
        val slowStart = StallWatchdog()
        assertFalse(slowStart.run(null, 30_000))
        val frozen = StallWatchdog()
        frozen.tick(5, 300)
        assertTrue(frozen.run(5, 30_000))
    }

    @Test
    fun anyProgressChangeResetsTheClock() {
        val w = StallWatchdog(freezeTimeoutMs = 20_000)
        w.tick(1, 300)
        assertFalse(w.run(1, 19_000))
        assertFalse(w.tick(2, 300))
        assertFalse(w.run(2, 19_000))
    }
}
