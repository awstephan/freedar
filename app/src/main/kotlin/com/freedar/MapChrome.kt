package com.freedar

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** UI adapter: input updates visibility synchronously, before the next timeout check. */
class ControlsPresentation {
    private val policy = MapControls(SystemClock::elapsedRealtime)
    var visible by mutableStateOf(true)
        private set
    fun update() { visible = policy.visible }
    fun reveal() { policy.reveal(); update() }
    fun hold(reason: ControlsHold, active: Boolean) { policy.hold(reason, active); update() }
}

@Composable
fun MapChrome(
    controls: ControlsPresentation,
    map: @Composable () -> Unit,
    status: @Composable () -> Unit,
    playback: @Composable () -> Unit,
    credits: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("map-controls", Context.MODE_PRIVATE) }
    var persistent by remember { mutableStateOf(preferences.getBoolean("persistent", false)) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val accessibility = remember { context.getSystemService(AccessibilityManager::class.java) }
    DisposableEffect(accessibility) {
        controls.hold(ControlsHold.TOUCH_EXPLORATION, accessibility.isTouchExplorationEnabled)
        val listener = AccessibilityManager.TouchExplorationStateChangeListener {
            controls.hold(ControlsHold.TOUCH_EXPLORATION, it)
        }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    SideEffect {
        controls.hold(ControlsHold.PERSISTENT, persistent)
        // Future Search/Legend surfaces share this hold for their entire open lifetime.
        controls.hold(ControlsHold.OVERLAY, settingsOpen)
        if (settingsOpen) controls.hold(ControlsHold.KEYBOARD, false)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controls, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { controls.update(); delay(50) }
        }
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        controls.hold(ControlsHold.KEYBOARD, it.type == KeyEventType.KeyDown)
        false
    }.pointerInput(controls) {
        // Observe, never consume: MapLibre and Compose controls retain their own gestures.
        awaitPointerEventScope {
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    controls.hold(ControlsHold.INTERACTION, event.changes.any { it.pressed })
                }
            } finally { controls.hold(ControlsHold.INTERACTION, false) }
        }
    }) {
        Box(Modifier.fillMaxSize().semantics {
            contentDescription = "Weather map"
            onClick("Show map controls") { controls.reveal(); true }
            traversalIndex = 0f
        }) { map() }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
            val landscape = maxWidth > maxHeight
            val transient: @Composable () -> Unit = {
                if (controls.visible) {
                    MapActions(settings = { settingsOpen = true })
                    playback()
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)
                        .semantics { isTraversalGroup = true; traversalIndex = 1f },
                        verticalArrangement = Arrangement.spacedBy(8.dp)) { status() }
                    Column(Modifier.weight(1f).fillMaxHeight()
                        .semantics { isTraversalGroup = true; traversalIndex = 2f },
                        verticalArrangement = Arrangement.Bottom) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { transient() }
                        credits()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f, fill = false)
                        .semantics { isTraversalGroup = true; traversalIndex = 1f }) { status() }
                    Column(Modifier.weight(1f, fill = false)
                        .semantics { isTraversalGroup = true; traversalIndex = 2f }) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { transient() }
                        credits()
                    }
                }
            }
        }
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Map controls") },
            text = {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(persistent, role = Role.Switch) {
                        persistent = it
                        preferences.edit().putBoolean("persistent", it).apply()
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep controls visible", Modifier.weight(1f))
                    Switch(checked = persistent, onCheckedChange = null)
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } },
        )
    }
}

/** Stable action positions; features supply their action when available. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapActions(
    settings: () -> Unit,
    locate: (() -> Unit)? = null,
    search: (() -> Unit)? = null,
    legend: (() -> Unit)? = null,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
            FlowRow {
                locate?.let { TextButton(onClick = it) { Text("Locate") } }
                search?.let { TextButton(onClick = it) { Text("Search") } }
                legend?.let { TextButton(onClick = it) { Text("Legend") } }
                TextButton(onClick = settings) { Text("Settings") }
            }
        }
    }
}
