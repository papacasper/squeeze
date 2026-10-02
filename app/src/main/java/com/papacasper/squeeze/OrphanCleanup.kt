package com.papacasper.squeeze

import java.io.File

/**
 * If the job process dies mid-encode (killed for memory, force-stopped) its pass and sample files stay in the cache,
 * and with an 8K source that can be hundreds of MB. Nothing owns them once the job is gone, so sweep them at launch.
 * Pure on its inputs so it can be unit-tested.
 */
object OrphanCleanup {
    /**
     * Deletes the job's working folder unless a job is still running or a finished result (or a killed batch's finished files)
     * is still waiting to be picked up; those files live in that folder. Returns the bytes freed.
     */
    fun sweep(cacheDir: File, filesDir: File, jobAlive: Boolean): Long {
        if (jobAlive || File(filesDir, "pending_result.json").exists() || File(filesDir, BatchProgress.FILE_NAME).exists()) return 0L
        val work = File(cacheDir, "compressed")
        if (!work.exists()) return 0L
        val bytes = work.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        work.deleteRecursively()
        return bytes
    }
}
