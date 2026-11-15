package com.freedar

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

internal class ControlsPresentation {
    private val policy = MapControls(SystemClock::elapsedRealtime)
    var visible by mutableStateOf(true)
        private set
    fun update() { visible = policy.visible }
    fun reveal() { policy.reveal(); update() }
    fun hold(reason: ControlsHold, active: Boolean) { policy.hold(reason, active); update() }
}

@Composable
internal fun rememberControlsPresentation(): ControlsPresentation {
    val controls = remember { ControlsPresentation() }
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    DisposableEffect(accessibility, controls) {
        controls.hold(ControlsHold.TOUCH_EXPLORATION, accessibility.isTouchExplorationEnabled)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener {
            controls.hold(ControlsHold.TOUCH_EXPLORATION, it)
        }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controls, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            controls.reveal()
            while (true) { controls.update(); delay(50) }
        }
    }
    return controls
}
