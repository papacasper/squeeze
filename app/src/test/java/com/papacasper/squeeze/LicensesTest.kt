package com.papacasper.squeeze

import org.junit.Assert.assertTrue
import org.junit.Test

class LicensesTest {
    @Test
    fun listsTheBundledGplAndMediaComponents() {
        val names = Licenses.components.map { it.name }
        assertTrue(names.any { it.contains("FFmpeg") })
        assertTrue(names.any { it.contains("yt-dlp") })
        assertTrue(names.any { it.contains("Media3") })
    }

    @Test
    fun everyEntryHasALicenseAndHttpsLink() {
        Licenses.components.forEach {
            assertTrue(it.name, it.license.isNotBlank())
            assertTrue(it.name, it.url.startsWith("https://"))
        }
    }

    @Test
    fun textVersionNamesTheAppLicenseAndEachComponent() {
        val text = Licenses.asText()
        assertTrue(text.contains(Licenses.APP_LICENSE))
        Licenses.components.forEach { assertTrue(text.contains(it.name)) }
    }
}
