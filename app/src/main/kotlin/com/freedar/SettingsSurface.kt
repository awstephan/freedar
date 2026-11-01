package com.freedar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.freedar.radar.PlaybackSpeed
import kotlin.math.roundToInt

/** Uses the chrome's bounded scroll region, leaving the map and anchored credits visible. */
@Composable
fun SettingsSurface(settings: AppearanceSettings, persistent: Boolean, persist: (Boolean) -> Unit,
    changeTheme: (MapTheme) -> Unit, dismiss: () -> Unit) {
    val imagery = (LocalContext.current.applicationContext as FreedarApplication).imagery
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = dismiss)
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = dismiss) { Text("Done") }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(persistent, role = Role.Switch, onValueChange = persist),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Keep controls visible", Modifier.weight(1f))
                Switch(checked = persistent, onCheckedChange = null)
            }
            Text("Radar opacity · ${(settings.opacity * 100).roundToInt()}%")
            Slider(value = settings.opacity, onValueChange = settings::opacity,
                modifier = Modifier.semantics { contentDescription = "Radar opacity" })
            TextButton(onClick = { settings.opacity(AppearanceSettings.DEFAULT_OPACITY) }) {
                Text("Reset opacity to 65%")
            }
            Text("65% balances precipitation and map readability. 0% hides radar.",
                style = MaterialTheme.typography.bodySmall)
            Text("Map and controls theme")
            for (theme in MapTheme.entries) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(settings.theme == theme, role = Role.RadioButton, onClick = { changeTheme(theme) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.theme == theme, onClick = null)
                    Text(theme.label)
                }
            }
            Text("System follows the device theme (default).", style = MaterialTheme.typography.bodySmall)
            Text("Playback speed")
            for (speed in PlaybackSpeed.entries) {
                val label = when (speed) { PlaybackSpeed.SLOW -> "Slow"; PlaybackSpeed.NORMAL -> "Normal"; PlaybackSpeed.FAST -> "Fast" }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(settings.speed == speed, role = Role.RadioButton, onClick = { settings.speed(speed) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.speed == speed, onClick = null)
                    Text("$label · ${speed.fps} fps")
                }
            }
            Text("Normal (2 fps) is the default. Newest frame always holds for one second.",
                style = MaterialTheme.typography.bodySmall)
            Text("About Freedar", style = MaterialTheme.typography.titleMedium)
            Text("Version ${BuildConfig.VERSION_NAME}")
            SourceLink("GitHub project", "https://github.com/awstephan/freedar")
            SourceLink("Radar: RainViewer", "https://www.rainviewer.com/")
            SourceLink("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright")
            SourceLink("© CARTO", "https://carto.com/attribution/")
            SourceLink("MapLibre", "https://maplibre.org/")
            SourceLink("Places: GeoNames · CC BY 4.0", "https://www.geonames.org/")
            SourceLink("GeoNames license", "https://creativecommons.org/licenses/by/4.0/")
            Text("Downloaded imagery", style = MaterialTheme.typography.titleMedium)
            Text("Downloaded map and radar · up to 100 MB total. Radar expires two hours after its frame time; base map within 30 days. Missing imagery does not mean clear weather.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !clearing, onClick = {
                clearing = true
                scope.launch {
                    downloadMessage = try {
                        withContext(Dispatchers.IO) { imagery.clear() }
                        "Downloaded imagery cleared"
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: java.io.IOException) { "Could not clear downloaded imagery. Try again." }
                    clearing = false
                }
            }) { Text(if (clearing) "Clearing…" else "Clear downloaded data") }
            downloadMessage?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text("Preserves remembered view, preferences and bundled place search. Visible map textures may remain until the map reloads.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SourceLink(label: String, url: String) {
    Text(androidx.compose.ui.text.buildAnnotatedString {
        pushLink(androidx.compose.ui.text.LinkAnnotation.Url(url))
        append(label)
        pop()
    }, style = MaterialTheme.typography.bodySmall)
}
