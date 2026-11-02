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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Persistent corner controls; detailed information only occupies the map when requested. */
@Composable
fun MapChrome(
    map: @Composable () -> Unit,
    status: @Composable () -> Unit,
    playback: @Composable () -> Unit,
    credits: @Composable () -> Unit,
    notice: String,
    warning: Boolean,
    locate: (() -> Unit)? = null,
    locating: Boolean = false,
    locateEnabled: Boolean = true,
    selectPlace: ((Place) -> Unit)? = null,
    appearance: AppearanceSettings,
    changeTheme: (MapTheme) -> Unit,
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var searchState by rememberSaveable(stateSaver = PlaceSearchState.Saver) { mutableStateOf(PlaceSearchState()) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().semantics {
            contentDescription = "Weather map"
            traversalIndex = 0f
        }) { map() }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp)) {
            MapIconButton(MapIcon.INFO, "Info", onClick = {
                settingsOpen = false; searchOpen = false; infoOpen = true
            }, warning = warning,
                modifier = Modifier.align(Alignment.TopStart), status = notice)
            Row(Modifier.align(Alignment.TopEnd), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                locate?.let {
                    MapIconButton(if (locating) MapIcon.CLOSE else MapIcon.LOCATE,
                        if (locating) "Cancel" else "Locate", it, enabled = locateEnabled)
                }
                selectPlace?.let {
                    MapIconButton(MapIcon.SEARCH, "Search", {
                        searchState = PlaceSearchState()
                        settingsOpen = false; infoOpen = false
                        searchOpen = true
                    })
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapIconButton(MapIcon.SETTINGS, "Settings", {
                    searchOpen = false; infoOpen = false; settingsOpen = true
                })
                Box(Modifier.weight(1f), contentAlignment = Alignment.BottomEnd) {
                    Box(Modifier.widthIn(max = 420.dp)) { playback() }
                }
            }
            if (settingsOpen || searchOpen || infoOpen) {
                // Bound the scroll area in both orientations, including large fonts and the keyboard.
                Column(Modifier.align(Alignment.Center).widthIn(max = 480.dp)
                    .fillMaxWidth().fillMaxHeight(.85f).verticalScroll(rememberScrollState())) {
                    when {
                        settingsOpen -> SettingsSurface(appearance, changeTheme, dismiss = { settingsOpen = false })
                        searchOpen && selectPlace != null -> PlaceSearchSurface(searchState, selectPlace,
                            dismiss = { searchOpen = false })
                        infoOpen -> {
                            BackHandler { infoOpen = false }
                            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Map info", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        TextButton(onClick = { infoOpen = false }) { Text("Close info") }
                                    }
                                    status()
                                    credits()
                                    LegendContent()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
