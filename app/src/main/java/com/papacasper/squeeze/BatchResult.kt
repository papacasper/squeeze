package com.papacasper.squeeze

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One file of a batch: either [done] or the [error] that stopped it. */
data class BatchItem(
    val sourceName: String,
    val originalBytes: Long,
    val done: CompressionState.Done?,
    val error: String? = null
)

/** What the service hands the UI when a batch ends, and how it is carried across the process boundary. */
object BatchResult {
    fun toJson(items: List<BatchItem>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("source", item.sourceName)
                put("original", item.originalBytes)
                item.error?.let { put("error", it) }
                item.done?.let { d ->
                    put("path", d.resultFile.absolutePath)
                    put("mime", d.mime)
                    put("fits", d.fitsTarget)
                    put("label", d.targetLabel)
                    put("name", d.suggestedName)
                    put("settings", d.settings)
                    if (d.extraFiles.isNotEmpty()) put("extras", JSONArray(d.extraFiles.map { it.absolutePath }))
                }
            })
        }
    }.toString()

    fun fromJson(text: String): List<BatchItem> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val original = o.optLong("original")
            val done = if (o.has("path")) CompressionState.Done(
                originalBytes = original,
                resultFile = File(o.getString("path")),
                mime = o.optString("mime"),
                fitsTarget = o.optBoolean("fits"),
                targetLabel = o.optString("label"),
                suggestedName = o.optString("name"),
                settings = o.optString("settings"),
                extraFiles = o.optJSONArray("extras")?.let { a -> (0 until a.length()).map { File(a.getString(it)) } }.orEmpty()
            ) else null
            BatchItem(o.optString("source"), original, done, o.optString("error").ifEmpty { null })
        }
    }

    /** One line for the top of the results: how many made it, and how many were over or failed. */
    fun summary(items: List<BatchItem>, targetLabel: String): String {
        val failed = items.count { it.done == null }
        val over = items.count { it.done != null && !it.done.fitsTarget }
        val ok = items.size - failed - over
        val parts = mutableListOf("$ok of ${items.size} fit under $targetLabel")
        if (over > 0) parts += "$over still over"
        if (failed > 0) parts += "$failed failed"
        return parts.joinToString(" · ")
    }

    /** The mime type to advertise when sharing these results together. */
    fun shareMime(items: List<BatchItem>): String {
        val mimes = items.mapNotNull { it.done?.mime }.distinct()
        return mimes.singleOrNull() ?: if (mimes.all { it.startsWith("image") }) "image/*" else "*/*"
    }
}
