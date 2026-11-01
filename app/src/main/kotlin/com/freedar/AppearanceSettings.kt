package com.freedar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.freedar.radar.PlaybackSpeed

enum class MapTheme(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Remembered choices, independent of the lifetime or orientation of the settings surface. */
class AppearanceSettings(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var opacity by mutableStateOf(preferences.getFloat("opacity", DEFAULT_OPACITY)
        .takeIf { it.isFinite() && it in 0f..1f } ?: DEFAULT_OPACITY)
        private set
    var speed by mutableStateOf(PlaybackSpeed.entries.firstOrNull {
        it.name == preferences.getString("speed", null)
    } ?: PlaybackSpeed.NORMAL)
        private set
    var theme by mutableStateOf(MapTheme.entries.firstOrNull {
        it.name == preferences.getString("theme", null)
    } ?: MapTheme.SYSTEM)
        private set

    fun theme(value: MapTheme) {
        theme = value
        preferences.edit().putString("theme", value.name).apply()
    }
    fun dark(systemDark: Boolean) = when (theme) {
        MapTheme.SYSTEM -> systemDark
        MapTheme.LIGHT -> false
        MapTheme.DARK -> true
    }

    fun opacity(value: Float) {
        if (!value.isFinite()) return
        opacity = value.coerceIn(0f, 1f)
        preferences.edit().putFloat("opacity", opacity).apply()
    }
    fun speed(value: PlaybackSpeed) {
        speed = value
        preferences.edit().putString("speed", value.name).apply()
    }
    companion object { const val DEFAULT_OPACITY = .65f }
}
