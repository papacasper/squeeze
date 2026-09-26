package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptBudgetTest {
    @Test fun eightKGetsShortBudget() = assertEquals(3, AttemptBudget.forSource(7680, 4320, 8))
    @Test fun uhdKeepsFullBudget() = assertEquals(8, AttemptBudget.forSource(3840, 2160, 8))
    @Test fun portraitEightKGetsShortBudget() = assertEquals(3, AttemptBudget.forSource(4320, 7680, 8))
    @Test fun neverRaisesTheDefault() = assertEquals(2, AttemptBudget.forSource(7680, 4320, 2))
    @Test fun messageNamesJobAndAlternative() {
        val m = ExitMessages.describe(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, "clip.mp4")
        assertTrue(m.contains("out of memory") && m.contains("clip.mp4") && m.contains("squeeze-cli"))
    }
}
