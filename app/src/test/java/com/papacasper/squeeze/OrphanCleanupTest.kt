package com.papacasper.squeeze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrphanCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun leftovers(cache: File): File {
        val work = File(cache, "compressed/item0").apply { mkdirs() }
        File(work, "pass2_compressed.mp4").writeBytes(ByteArray(1000))
        File(cache, "compressed/sample_compressed.mp4").writeBytes(ByteArray(500))
        return File(cache, "compressed")
    }

    @Test
    fun removesLeftoversWhenNothingIsRunningOrWaiting() {
        val cache = tmp.newFolder(); val files = tmp.newFolder()
        val work = leftovers(cache)
        assertEquals(1500L, OrphanCleanup.sweep(cache, files, jobAlive = false))
        assertFalse(work.exists())
    }

    @Test
    fun keepsFilesWhileTheJobIsStillRunning() {
        val cache = tmp.newFolder(); val files = tmp.newFolder()
        val work = leftovers(cache)
        assertEquals(0L, OrphanCleanup.sweep(cache, files, jobAlive = true))
        assertTrue(work.exists())
    }

    @Test
    fun keepsFilesThatAWaitingResultPointsAt() {
        val cache = tmp.newFolder(); val files = tmp.newFolder()
        File(files, "pending_result.json").writeText("{}")
        val work = leftovers(cache)
        assertEquals(0L, OrphanCleanup.sweep(cache, files, jobAlive = false))
        assertTrue(work.exists())
    }

    @Test
    fun nothingToDoWithoutAWorkFolder() {
        assertEquals(0L, OrphanCleanup.sweep(tmp.newFolder(), tmp.newFolder(), jobAlive = false))
    }
}
