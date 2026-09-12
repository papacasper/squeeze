package com.papacasper.discordcompressor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val timestampMs: Long,
    val fileName: String,
    val originalBytes: Long,
    val resultBytes: Long,
    val targetLabel: String,
    val fitsTarget: Boolean
)

object HistoryStore {
    private const val PREFS_NAME = "squeeze_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 20

    fun addEntry(context: Context, entry: HistoryEntry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val entries = getEntries(context).toMutableList()
        entries.add(0, entry)
        val trimmed = entries.take(MAX_ENTRIES)

        val array = JSONArray()
        trimmed.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("timestampMs", e.timestampMs)
                    put("fileName", e.fileName)
                    put("originalBytes", e.originalBytes)
                    put("resultBytes", e.resultBytes)
                    put("targetLabel", e.targetLabel)
                    put("fitsTarget", e.fitsTarget)
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    fun getEntries(context: Context): List<HistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(
                    timestampMs = obj.getLong("timestampMs"),
                    fileName = obj.getString("fileName"),
                    originalBytes = obj.getLong("originalBytes"),
                    resultBytes = obj.getLong("resultBytes"),
                    targetLabel = obj.getString("targetLabel"),
                    fitsTarget = obj.getBoolean("fitsTarget")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
