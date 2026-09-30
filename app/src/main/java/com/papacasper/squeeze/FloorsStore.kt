package com.papacasper.squeeze

import android.content.Context

/** Persists the user's quality floors. Read in the UI process and handed to the compress process by intent. */
object FloorsStore {
    private const val PREFS = "floors"
    val HEIGHTS = intArrayOf(480, 720, 1080)
    val FPS = floatArrayOf(15f, 24f, 30f)
    val AUDIO = longArrayOf(32_000L, 48_000L, 64_000L)

    fun load(context: Context): BitrateMath.Floors {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = BitrateMath.Floors()
        return BitrateMath.Floors(
            minHeight = prefs.getInt("height", d.minHeight).takeIf { it in HEIGHTS } ?: d.minHeight,
            minFps = prefs.getFloat("fps", d.minFps).takeIf { it in FPS.toList() } ?: d.minFps,
            minAudioBitrate = prefs.getLong("audio", d.minAudioBitrate).takeIf { it in AUDIO.toList() } ?: d.minAudioBitrate
        )
    }

    fun save(context: Context, floors: BitrateMath.Floors) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("height", floors.minHeight)
            .putFloat("fps", floors.minFps)
            .putLong("audio", floors.minAudioBitrate)
            .apply()
    }
}
