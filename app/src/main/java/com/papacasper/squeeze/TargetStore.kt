package com.papacasper.squeeze

import android.content.Context

/** Remembers the last size target so the screen can highlight it and the custom slider comes back where it was. */
object TargetStore {
    private const val PREFS = "target"

    fun lastPreset(context: Context): Preset? {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("preset", null)
        return Preset.entries.firstOrNull { it.name == name }
    }

    fun loadFraction(context: Context, default: Float): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("fraction", default).coerceIn(0f, 1f)

    /** [preset] null means a custom size was used. */
    fun save(context: Context, preset: Preset?, fraction: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("preset", preset?.name)
            .putFloat("fraction", fraction)
            .apply()
    }
}
