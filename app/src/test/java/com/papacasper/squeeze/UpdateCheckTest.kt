package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckTest {
    @Test fun newerReleaseIsReported() {
        assertEquals("2026.10.10", UpdateCheck.newer("2026.10.09b", "v2026.10.10"))
        assertEquals("2026.10.09c", UpdateCheck.newer("2026.10.09b", "v2026.10.09c"))
        assertEquals("2026.10.09b", UpdateCheck.newer("2026.10.09", "v2026.10.09b"))
    }

    @Test fun sameOlderOrOddTagsAreIgnored() {
        assertNull(UpdateCheck.newer("2026.10.09b", "v2026.10.09b"))
        assertNull(UpdateCheck.newer("2026.10.09b", "v2026.10.09"))
        assertNull(UpdateCheck.newer("2026.10.09b", "cli-v0.2.1"))
        assertNull(UpdateCheck.newer("2026.10.09b-debug", "v2026.10.10"))
        assertNull(UpdateCheck.newer("2026.10.09b", null))
    }
}
