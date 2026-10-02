package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassProjectionTest {
    @Test fun projectsLinearly() = assertEquals(20_000_000L, PassProjection.projectedBytes(10_000_000, 0.5f))

    @Test fun noProjectionBeforeAnyProgress() = assertEquals(0L, PassProjection.projectedBytes(5, 0f))

    @Test fun clearMissAfterEnoughProgressStopsThePass() =
        assertEquals(40_000_000L, PassProjection.abortWith(20_000_000, 0.5f, 20_000_000))

    @Test fun tooEarlyToJudge() = assertNull(PassProjection.abortWith(10_000_000, 0.3f, 20_000_000))

    @Test fun smallOvershootRunsToTheEnd() = assertNull(PassProjection.abortWith(12_000_000, 0.4f, 20_000_000)) // 30 MB projected vs 20 MB goal: not 1.5x

    @Test fun onTargetAndUnderNeverStop() {
        assertNull(PassProjection.abortWith(5_000_000, 0.4f, 20_000_000))
        assertNull(PassProjection.abortWith(1_000_000, 0.5f, 20_000_000))
    }

    @Test fun finishedOrEmptyOrUnknownGoalNeverStop() {
        assertNull(PassProjection.abortWith(30_000_000, 1f, 20_000_000))
        assertNull(PassProjection.abortWith(0, 0.5f, 20_000_000))
        assertNull(PassProjection.abortWith(30_000_000, 0.5f, 0))
    }
}
