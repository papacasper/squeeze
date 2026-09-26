package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GifMathTest {
    @Test fun nearMissRaisesToleranceBeforeShrinking() {
        val step = GifMath.nextStep(480, 0, 1_500_000, 1_000_000, 160)!!
        assertEquals(GifMath.Step(480, 1), step)
    }

    @Test fun farMissShrinksWidthProportionally() {
        val step = GifMath.nextStep(480, 0, 8_000_000, 1_000_000, 160)!!
        assertTrue(step.width < 480 && step.width >= 160)
        assertTrue("tolerance bumped when shrinking", step.toleranceIndex >= 1)
        assertEquals(0, step.width % 2)
    }

    @Test fun shrinkAlwaysMakesProgressAboveMinimum() {
        // Barely over target with tolerance exhausted must still reduce the width.
        val last = GifMath.TOLERANCES.lastIndex
        val step = GifMath.nextStep(480, last, 1_010_000, 1_000_000, 160)!!
        assertTrue(step.width < 480)
    }

    @Test fun exhaustedLadderReturnsNull() {
        assertNull(GifMath.nextStep(160, GifMath.TOLERANCES.lastIndex, 5_000_000, 1_000_000, 160))
    }

    @Test fun atMinimumWidthTolerancePerformsLastResort() {
        assertEquals(GifMath.Step(160, 1), GifMath.nextStep(160, 0, 5_000_000, 1_000_000, 160))
    }
}
