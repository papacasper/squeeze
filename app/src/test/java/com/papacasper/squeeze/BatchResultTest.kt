package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatchResultTest {
    private fun done(name: String, fits: Boolean, mime: String = "video/mp4", original: Long = 100) = CompressionState.Done(
        originalBytes = original, resultFile = File("/cache/$name"), mime = mime, fitsTarget = fits,
        targetLabel = "Email (20 MB)", suggestedName = "$name-squeezed.mp4", settings = "720p"
    )

    private val items = listOf(
        BatchItem("a.mp4", 100, done("a", true)),
        BatchItem("b.mp4", 200, done("b", false, original = 200)),
        BatchItem("c.mp4", 300, null, "Couldn't decode")
    )

    @Test
    fun json_roundTripKeepsEverything() {
        val back = BatchResult.fromJson(BatchResult.toJson(items))
        assertEquals(items, back)
    }

    @Test
    fun json_failedItemHasNoDone() {
        val back = BatchResult.fromJson(BatchResult.toJson(items))
        assertNull(back[2].done)
        assertEquals("Couldn't decode", back[2].error)
    }

    @Test
    fun json_garbageGivesEmpty() {
        assertTrue(BatchResult.fromJson("not json").isEmpty())
    }

    @Test
    fun summary_countsFitOverAndFailed() {
        assertEquals("1 of 3 fit under X · 1 still over · 1 failed", BatchResult.summary(items, "X"))
        assertEquals("2 of 2 fit under X", BatchResult.summary(items.take(1) + items[0], "X"))
    }

    @Test
    fun shareMime_sameTypeOrWildcard() {
        assertEquals("video/mp4", BatchResult.shareMime(listOf(items[0], items[1])))
        val mixed = listOf(items[0], BatchItem("p.jpg", 5, done("p", true, "image/jpeg")))
        assertEquals("*/*", BatchResult.shareMime(mixed))
        val images = listOf(BatchItem("p.jpg", 5, done("p", true, "image/jpeg")), BatchItem("q.png", 5, done("q", true, "image/gif")))
        assertEquals("image/*", BatchResult.shareMime(images))
    }
}
