package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormatTest {
    @Test fun defaultCapsAt1080() {
        assertEquals("bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b", DownloadFormat.selector())
    }

    @Test fun fallsBackToUncappedWhenNothingFits() {
        assertTrue(DownloadFormat.selector(720).endsWith("/bv*+ba/b"))
        assertTrue(DownloadFormat.selector(720).startsWith("bv*[height<=720]"))
    }
}
