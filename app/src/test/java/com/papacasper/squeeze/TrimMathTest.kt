package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimMathTest {
    @Test fun fullRangeIsNotTrimmed() {
        assertFalse(TrimMath.isTrimmed(0, 60_000, 60_000))
        assertFalse(TrimMath.isTrimmed(0, 59_900, 60_000))
    }

    @Test fun subRangeIsTrimmed() {
        assertTrue(TrimMath.isTrimmed(5_000, 60_000, 60_000))
        assertTrue(TrimMath.isTrimmed(0, 30_000, 60_000))
    }

    @Test fun unknownDurationOrEmptyWindowIsNotTrimmed() {
        assertFalse(TrimMath.isTrimmed(0, 0, 0))
        assertFalse(TrimMath.isTrimmed(10, 10, 60_000))
    }

    @Test fun capLengthPullsEndIn() {
        assertEquals(10_000L to 40_000L, TrimMath.capLength(10_000, 90_000, 30_000))
        assertEquals(10_000L to 20_000L, TrimMath.capLength(10_000, 20_000, 30_000))
    }

    @Test fun effectiveDuration() {
        assertEquals(20_000L, TrimMath.effectiveDurationMs(10_000, 30_000, 60_000))
        assertEquals(60_000L, TrimMath.effectiveDurationMs(0, 60_000, 60_000))
    }
}
