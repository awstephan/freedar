package com.freedar

enum class ControlsHold { INTERACTION, KEYBOARD, FOCUS, TOUCH_EXPLORATION, PERSISTENT, PANEL, LOCATING, BACKGROUND }

/** Monotonic idle time; playback and radar updates never count as interaction. */
class MapControls(private val now: () -> Long) {
    private var lastInteraction = now()
    private val holds = mutableSetOf<ControlsHold>()
    val visible: Boolean get() = holds.isNotEmpty() || now() - lastInteraction < 5000
    fun reveal() { lastInteraction = now() }
    fun hold(reason: ControlsHold, active: Boolean) {
        val changed = if (active) holds.add(reason) else holds.remove(reason)
        if (changed) reveal()
    }
}
