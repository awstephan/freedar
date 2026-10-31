package com.freedar

enum class ControlsHold { INTERACTION, KEYBOARD, TOUCH_EXPLORATION, PERSISTENT, OVERLAY }

/** App-level visibility policy. Time is monotonic; radar/playback updates are not interaction. */
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
