package com.papacasper.squeeze

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Remembers what the short calibration sample measured for a source, so compressing the same file again (a retry,
 * a cancelled run, the same preset after a tweak) doesn't spend minutes re-encoding the sample. What the sample
 * measures depends on the encoder settings it ran with, so those are part of the key.
 */
object CalibrationCache {
    data class Key(
        val source: String,
        val sourceBytes: Long,
        val trimStartMs: Long,
        val trimDurationMs: Long,
        val height: Int,
        val fps: Float?,
        val sampleSec: Double
    )

    /** The sample was encoded at [bitrate] and came out [sampleBytes] long (audio included, at [audioBitrate]). */
    data class Entry(val bitrate: Long, val sampleBytes: Long, val audioBitrate: Long)

    private const val MAX_ENTRIES = 24
    private fun file(dir: File) = File(dir, "calibration.json")

    fun get(dir: File, key: Key): Entry? =
        read(dir).firstOrNull { it.first == key }?.second

    fun put(dir: File, key: Key, entry: Entry) {
        val all = read(dir).filterNot { it.first == key } + (key to entry)
        write(dir, all.takeLast(MAX_ENTRIES))
    }

    private fun read(dir: File): List<Pair<Key, Entry>> = runCatching {
        val arr = JSONArray(file(dir).readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Key(
                o.getString("source"), o.getLong("sourceBytes"), o.getLong("trimStartMs"), o.getLong("trimDurationMs"),
                o.getInt("height"), if (o.has("fps")) o.getDouble("fps").toFloat() else null, o.getDouble("sampleSec")
            ) to Entry(o.getLong("bitrate"), o.getLong("sampleBytes"), o.getLong("audioBitrate"))
        }
    }.getOrDefault(emptyList())

    private fun write(dir: File, all: List<Pair<Key, Entry>>) {
        val arr = JSONArray()
        all.forEach { (k, e) ->
            arr.put(
                JSONObject().put("source", k.source).put("sourceBytes", k.sourceBytes).put("trimStartMs", k.trimStartMs)
                    .put("trimDurationMs", k.trimDurationMs).put("height", k.height).put("sampleSec", k.sampleSec)
                    .apply { k.fps?.let { put("fps", it.toDouble()) } }
                    .put("bitrate", e.bitrate).put("sampleBytes", e.sampleBytes).put("audioBitrate", e.audioBitrate)
            )
        }
        runCatching { file(dir).writeText(arr.toString()) }
    }
}
