package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchProgressTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun done(file: File) = CompressionState.Done(100, file, "video/mp4", true, "Email (20 MB)", "x-squeezed.mp4", emptyList(), "720p")

    @Test
    fun withRemaining_marksTheUnrunFilesNotFinished() {
        val finished = listOf(BatchItem("a.mp4", 10, done(File("/x/a"))))
        val all = BatchProgress.withRemaining(finished, listOf("b.mp4" to 20L, "c.mp4" to 30L))
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), all.map { it.sourceName })
        assertEquals(BatchProgress.NOT_FINISHED, all[1].error)
        assertNull(all[2].done)
    }

    @Test
    fun roundTrip_keepsFinishedAndPlaceholders() {
        val dir = tmp.newFolder(); val result = tmp.newFile("a-out.mp4")
        BatchProgress.write(dir, "Email (20 MB)", BatchProgress.withRemaining(listOf(BatchItem("a.mp4", 10, done(result))), listOf("b.mp4" to 20L)))
        val (label, items) = BatchProgress.read(dir)!!
        assertEquals("Email (20 MB)", label)
        assertEquals(result, items[0].done?.resultFile)
        assertEquals(BatchProgress.NOT_FINISHED, items[1].error)
    }

    @Test
    fun read_isNullWhenNoFinishedFileSurvives() {
        val dir = tmp.newFolder()
        BatchProgress.write(dir, "L", listOf(BatchItem("a.mp4", 10, done(File("/gone/a.mp4")))))
        assertNull(BatchProgress.read(dir))
    }

    @Test
    fun read_turnsAMissingFinishedFileIntoAFailure() {
        val dir = tmp.newFolder(); val alive = tmp.newFile("b-out.mp4")
        BatchProgress.write(dir, "L", listOf(BatchItem("a.mp4", 10, done(File("/gone/a.mp4"))), BatchItem("b.mp4", 10, done(alive))))
        val items = BatchProgress.read(dir)!!.second
        assertNull(items[0].done)
        assertTrue(items[1].done != null)
    }

    @Test
    fun read_isNullWithoutAFileAndClearRemovesIt() {
        val dir = tmp.newFolder()
        assertNull(BatchProgress.read(dir))
        val f = tmp.newFile("o.mp4")
        BatchProgress.write(dir, "L", listOf(BatchItem("a", 1, done(f))))
        BatchProgress.clear(dir)
        assertNull(BatchProgress.read(dir))
    }
}
