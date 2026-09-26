package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextTest {
    @Test fun bareUrl() = assertEquals("https://youtu.be/abc", SharedText.firstUrl("https://youtu.be/abc"))

    @Test fun urlInsideProseLosesTrailingPunctuation() =
        assertEquals("https://example.com/v?id=1", SharedText.firstUrl("Watch this https://example.com/v?id=1!"))

    @Test fun firstOfSeveral() =
        assertEquals("http://a.com/x", SharedText.firstUrl("http://a.com/x and https://b.com/y"))

    @Test fun noUrl() {
        assertNull(SharedText.firstUrl("just words"))
        assertNull(SharedText.firstUrl(null))
    }
}
