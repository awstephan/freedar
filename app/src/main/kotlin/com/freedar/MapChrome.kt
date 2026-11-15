package com.freedar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private enum class MapPanel { ATTRIBUTIONS, INFO, SETTINGS, SEARCH }

/** Idle controls yield the full map; panels and active input hold them visible. */
@Composable
fun MapChrome(
    map: @Composable () -> Unit,
    status: @Composable () -> Unit,
    playback: @Composable () -> Unit,
    attributions: @Composable () -> Unit,
    notice: String,
    warning: Boolean,
    locate: (() -> Unit)? = null,
    locating: Boolean = false,
    locateEnabled: Boolean = true,
    selectPlace: ((Place) -> Unit)? = null,
    appearance: AppearanceSettings,
    changeTheme: (MapTheme) -> Unit,
    imageryLoading: Boolean = false,
    foreground: Boolean = true,
    dark: Boolean = false,
) {
    var panel by rememberSaveable { mutableStateOf<MapPanel?>(null) }
    val controls = rememberControlsPresentation()
    var panelBounds by remember { mutableStateOf<Rect?>(null) }
    val toggleBounds = remember { mutableMapOf<MapPanel, Rect>() }
    fun Modifier.panelToggle(next: MapPanel) = onGloballyPositioned { toggleBounds[next] = it.boundsInRoot() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun close() { keyboard?.hide(); panel = null }
    fun toggle(next: MapPanel) {
        controls.reveal()
        keyboard?.hide()
        panel = if (panel == next) null else next
    }
    var searchState by rememberSaveable(stateSaver = PlaceSearchState.Saver) { mutableStateOf(PlaceSearchState()) }
    val autoHide = appearance.autoHideControls
    val panelOpen = panel != null
    SideEffect {
        controls.hold(ControlsHold.PANEL, panelOpen)
        controls.hold(ControlsHold.LOCATING, locating)
        controls.hold(ControlsHold.PERSISTENT, !autoHide)
        controls.hold(ControlsHold.BACKGROUND, !foreground)
    }
    val dismissOutside by rememberUpdatedState<(Offset) -> Unit>({ point ->
        // Panel buttons handle replacement/toggling on click; closing on down would reopen the same panel.
        if (panel != null && panelBounds?.contains(point) != true &&
            toggleBounds.values.none { it.contains(point) }) close()
    })
    Box(Modifier.fillMaxSize().onPreviewKeyEvent {
        controls.reveal()
        controls.hold(ControlsHold.KEYBOARD, it.type == KeyEventType.KeyDown)
        false
    }.pointerInput(controls) {
        // Observe before children, never consume: native map gestures still receive the full stream.
        awaitPointerEventScope {
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // Layout-generated hover enter/exit events are not user activity.
                    if (event.type != PointerEventType.Enter && event.type != PointerEventType.Exit) controls.reveal()
                    controls.hold(ControlsHold.INTERACTION, event.changes.any { it.pressed })
                    event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let { dismissOutside(it.position) }
                }
            } finally { controls.hold(ControlsHold.INTERACTION, false) }
        }
    }) {
        Box(Modifier.fillMaxSize().semantics {
            contentDescription = "Weather map"
            onClick("Show map controls") { controls.reveal(); true }
            traversalIndex = 0f
        }) { map() }
        if (imageryLoading && foreground && panel == null) {
            ImageryLoadingIndicator(dark, Modifier.align(Alignment.Center))
        }
        if (controls.visible) Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp)
            .onFocusChanged { controls.hold(ControlsHold.FOCUS, it.hasFocus) }.focusGroup()) {
            MapIconButton(MapIcon.ATTRIBUTIONS, "Attributions", onClick = { toggle(MapPanel.ATTRIBUTIONS) },
                modifier = Modifier.align(Alignment.TopStart).zIndex(1f).panelToggle(MapPanel.ATTRIBUTIONS), expanded = panel == MapPanel.ATTRIBUTIONS)
            Row(Modifier.align(Alignment.TopEnd).zIndex(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                locate?.let {
                    MapIconButton(if (locating) MapIcon.CLOSE else MapIcon.LOCATE,
                        if (locating) "Cancel" else "Locate", it, enabled = locateEnabled)
                }
                selectPlace?.let {
                    MapIconButton(MapIcon.SEARCH, "Search", {
                        if (panel != MapPanel.SEARCH) searchState = PlaceSearchState()
                        toggle(MapPanel.SEARCH)
                    }, modifier = Modifier.panelToggle(MapPanel.SEARCH), expanded = panel == MapPanel.SEARCH)
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(1f),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    MapIconButton(MapIcon.SETTINGS, "Settings", { toggle(MapPanel.SETTINGS) },
                        modifier = Modifier.panelToggle(MapPanel.SETTINGS), expanded = panel == MapPanel.SETTINGS)
                    MapIconButton(MapIcon.INFO, "Info", { toggle(MapPanel.INFO) }, warning = warning,
                        modifier = Modifier.panelToggle(MapPanel.INFO), status = notice, expanded = panel == MapPanel.INFO)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.BottomEnd) {
                    Box(Modifier.widthIn(max = 420.dp)) { playback() }
                }
            }
            if (panel != null) {
                BackHandler { close() }
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // A landscape keyboard can leave too little room between control rows.
                    // In that case put the scrolling panel between the corner controls instead.
                    val shortViewport = maxHeight < 240.dp
                    val panelPadding = if (shortViewport) Modifier.padding(horizontal = 104.dp)
                        else Modifier.padding(top = 56.dp, bottom = 96.dp)
                    Box(panelPadding, contentAlignment = Alignment.Center) {
                        key(panel) {
                            Column(Modifier.widthIn(max = 480.dp).onGloballyPositioned { panelBounds = it.boundsInRoot() }
                                .verticalScroll(rememberScrollState())) {
                                when {
                                    panel == MapPanel.SETTINGS -> SettingsSurface(appearance, changeTheme, dismiss = ::close)
                                    panel == MapPanel.SEARCH && selectPlace != null -> PlaceSearchSurface(searchState, selectPlace,
                                        dismiss = ::close)
                                    panel == MapPanel.INFO || panel == MapPanel.ATTRIBUTIONS -> {
                                        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
                                            Column(Modifier.width(IntrinsicSize.Max).padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(if (panel == MapPanel.INFO) "Map info" else "Attributions",
                                                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                                    TextButton(onClick = ::close) {
                                                        Text(if (panel == MapPanel.INFO) "Close info" else "Close attributions")
                                                    }
                                                }
                                                if (panel == MapPanel.ATTRIBUTIONS) attributions() else {
                                                    Text("Radar status", style = MaterialTheme.typography.titleSmall)
                                                    status()
                                                    LegendContent()
                                                    Text("Map points", style = MaterialTheme.typography.titleSmall)
                                                    Text("Blue: your latest location fix (updates with Locate). Purple: selected search result.",
                                                        style = MaterialTheme.typography.bodySmall)
                                                    Text("Coverage & limitations", style = MaterialTheme.typography.titleSmall)
                                                    Text("Zoom adds no radar detail. Shading marks outside the static footprint; " +
                                                        "live coverage elsewhere is unknown. Blank radar does not mean clear weather.",
                                                        style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
