package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MediaUtilsTest {

    @Test
    fun formatSize_units() {
        assertEquals("0 B", formatSize(0))
        assertEquals("0 B", formatSize(-5))
        assertEquals("512.0 B", formatSize(512))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("20.0 MB", formatSize(20L * 1024 * 1024))
        assertEquals("2.0 GB", formatSize(2L * 1024 * 1024 * 1024))
        assertEquals("2048.0 GB", formatSize(2048L * 1024 * 1024 * 1024))
    }

    @Test
    fun squeezedName_usesOriginalStemAndResultExtension() {
        assertEquals("holiday-squeezed.mp4", squeezedName("holiday.mov", File("compressed.mp4")))
        assertEquals("a.b-squeezed.jpg", squeezedName("a.b.png", File("x.jpg")))
    }

    @Test
    fun squeezedName_fallbacks() {
        assertEquals("squeeze-squeezed.mp4", squeezedName(null, File("x.mp4")))
        assertEquals("squeeze-squeezed.mp4", squeezedName("", File("x.mp4")))
        assertEquals("noext-squeezed.gif", squeezedName("noext", File("x.gif")))
        assertEquals("clip-squeezed.bin", squeezedName("clip.mp4", File("noextension")))
        assertEquals(".hidden-squeezed.mp4", squeezedName(".hidden", File("x.mp4")))
    }
}
