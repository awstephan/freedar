package com.freedar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

private enum class MapPanel { ATTRIBUTIONS, INFO, SETTINGS, SEARCH }

/** Persistent corner controls; detailed information only occupies the map when requested. */
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
    val keyboard = LocalSoftwareKeyboardController.current
    fun close() { keyboard?.hide(); panel = null }
    fun toggle(next: MapPanel) {
        keyboard?.hide()
        panel = if (panel == next) null else next
    }
    var searchState by rememberSaveable(stateSaver = PlaceSearchState.Saver) { mutableStateOf(PlaceSearchState()) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().semantics {
            contentDescription = "Weather map"
            traversalIndex = 0f
        }) { map() }
        if (imageryLoading && foreground && panel == null) {
            ImageryLoadingIndicator(dark, Modifier.align(Alignment.Center))
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp)) {
            MapIconButton(MapIcon.ATTRIBUTIONS, "Attributions", onClick = { toggle(MapPanel.ATTRIBUTIONS) },
                modifier = Modifier.align(Alignment.TopStart).zIndex(1f), expanded = panel == MapPanel.ATTRIBUTIONS)
            Row(Modifier.align(Alignment.TopEnd).zIndex(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                locate?.let {
                    MapIconButton(if (locating) MapIcon.CLOSE else MapIcon.LOCATE,
                        if (locating) "Cancel" else "Locate", it, enabled = locateEnabled)
                }
                selectPlace?.let {
                    MapIconButton(MapIcon.SEARCH, "Search", {
                        if (panel != MapPanel.SEARCH) searchState = PlaceSearchState()
                        toggle(MapPanel.SEARCH)
                    }, expanded = panel == MapPanel.SEARCH)
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(1f),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    MapIconButton(MapIcon.SETTINGS, "Settings", { toggle(MapPanel.SETTINGS) }, expanded = panel == MapPanel.SETTINGS)
                    MapIconButton(MapIcon.INFO, "Info", { toggle(MapPanel.INFO) }, warning = warning,
                        status = notice, expanded = panel == MapPanel.INFO)
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
                            Column(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState())) {
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
