package com.papacasper.squeeze

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepOriginalTest {
    private val mb = 1_000_000L

    @Test fun keepsWhenOriginalFitsAndResultIsLarger() = assertTrue(KeepOriginal.shouldKeep(2 * mb, 3 * mb, 20 * mb, true))
    @Test fun keepsWhenResultIsTheSameSize() = assertTrue(KeepOriginal.shouldKeep(2 * mb, 2 * mb, 20 * mb, true))
    @Test fun usesTheResultWhenItIsSmaller() = assertFalse(KeepOriginal.shouldKeep(5 * mb, 2 * mb, 20 * mb, true))
    @Test fun usesTheResultWhenTheOriginalDoesNotFit() = assertFalse(KeepOriginal.shouldKeep(30 * mb, 35 * mb, 20 * mb, true))
    @Test fun neverKeepsWhenSizesAreNotComparable() = assertFalse(KeepOriginal.shouldKeep(2 * mb, 3 * mb, 20 * mb, false))
    @Test fun ignoresAnUnknownOriginalSize() = assertFalse(KeepOriginal.shouldKeep(0, 3 * mb, 20 * mb, true))
    @Test fun recognisesItsOwnNote() {
        assertTrue(KeepOriginal.isKept(KeepOriginal.NOTE))
        assertFalse(KeepOriginal.isKept("720p · 24 fps"))
    }
}
