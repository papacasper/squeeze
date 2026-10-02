package com.papacasper.squeeze

import org.json.JSONObject
import java.io.File

/**
 * A batch's results so far, kept on disk while it runs. The job process reports a batch only when it ends, so if
 * the process is killed partway the finished files would otherwise be lost: the next launch reads this to show
 * them (with the files that never ran marked as not finished). Written by the job process, read by the UI process.
 */
object BatchProgress {
    const val FILE_NAME = "batch_progress.json"
    const val NOT_FINISHED = "Not finished: Squeeze was closed first."

    /** [finished] followed by a not-finished entry for each of [remaining] (name to original size). */
    fun withRemaining(finished: List<BatchItem>, remaining: List<Pair<String, Long>>): List<BatchItem> =
        finished + remaining.map { (name, bytes) -> BatchItem(name, bytes, null, NOT_FINISHED) }

    fun write(filesDir: File, label: String, items: List<BatchItem>) {
        runCatching {
            File(filesDir, FILE_NAME).writeText(
                JSONObject().put("label", label).put("items", BatchResult.toJson(items)).toString()
            )
        }
    }

    /** The saved batch, or null if none, or none of its finished files still exist (then there is nothing to show). */
    fun read(filesDir: File): Pair<String, List<BatchItem>>? {
        val json = runCatching { JSONObject(File(filesDir, FILE_NAME).readText()) }.getOrNull() ?: return null
        val items = BatchResult.fromJson(json.optString("items"))
        if (items.none { it.done?.resultFile?.exists() == true }) return null
        return json.optString("label") to items.map { item ->
            // A finished item whose file has gone is a failure now, not a result to offer.
            if (item.done != null && !item.done.resultFile.exists()) BatchItem(item.sourceName, item.originalBytes, null, "The compressed file is gone.") else item
        }
    }

    fun clear(filesDir: File) {
        File(filesDir, FILE_NAME).delete()
    }
}
