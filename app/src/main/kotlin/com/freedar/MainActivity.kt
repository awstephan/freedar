package com.freedar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freedar.radar.LatestRadar
import com.freedar.radar.RadarState
import com.freedar.radar.RainViewer
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var map: RadarMap
    private lateinit var radar: LatestRadar
    private var baseMessage by mutableStateOf<String?>("Loading base map…")
    private var styleReady = false
    private var refreshJob: Job? = null
    private var restored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val client = (application as FreedarApplication).httpClient
        map = RadarMap(this)
        map.view.onCreate(savedInstanceState)
        restored = savedInstanceState != null
        radar = LatestRadar(RainViewer(client), Clock.systemUTC(), map)
        setContent {
            MaterialTheme {
                val state by radar.state.collectAsState()
                val tileStatus by map.status.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { map.view }, modifier = Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp),
                        verticalArrangement = Arrangement.SpaceBetween) {
                        RadarStatus(state, baseMessage, tileStatus, ::refresh)
                        Credits()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                refresh()
                try {
                    while (true) {
                        radar.updateAge()
                        delay(30_000) // Foreground age display only; no periodic network polling.
                    }
                } finally {
                    refreshJob?.cancel()
                }
            }
        }
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            if (!styleReady) {
                baseMessage = "Loading base map…"
                try {
                    val key = BuildConfig.CARTO_API_KEY
                    map.loadStyle(if (key.isBlank()) null else
                        "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json")
                    if (!restored) map.overview()
                    styleReady = true
                    baseMessage = if (key.isBlank()) "Base map needs the developer's CARTO key. See build instructions." else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    baseMessage = "Base map unavailable. Check connection and Retry."
                    return@launch
                }
            }
            radar.refresh()
        }
    }

    @Composable private fun RadarStatus(state: RadarState, base: String?, tiles: String?, retry: () -> Unit) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Freedar", style = MaterialTheme.typography.titleMedium)
                    val frame = state.frame
                    if (frame != null) {
                        val date = Date.from(frame.time)
                        val localTime = android.text.format.DateFormat.getTimeFormat(this@MainActivity).format(date)
                        val day = android.text.format.DateFormat.getMediumDateFormat(this@MainActivity).format(date)
                        val zone = DateTimeFormatter.ofPattern("z").withZone(ZoneId.systemDefault()).format(frame.time)
                        Text("Frame time · $day, $localTime $zone", style = MaterialTheme.typography.bodyMedium)
                        Text("${state.ageSeconds / 60} min old${if (state.stale) " · Stale radar feed" else ""}",
                            style = MaterialTheme.typography.bodySmall)
                    } else Text(if (state.loading) "Loading radar…" else "Radar unavailable")
                    if (state.loading && frame != null) Text("Updating radar…", style = MaterialTheme.typography.bodySmall)
                    listOfNotNull(base, state.error, tiles).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = retry, enabled = !state.loading) {
                    Text(if (state.error != null || base != null || tiles != null) "Retry" else "Refresh")
                }
            }
        }
    }

    @Composable private fun Credits() {
        Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp) {
            Column(Modifier.padding(10.dp)) {
                Text("Closer zoom enlarges radar; it adds no detail. Blank areas may lack coverage.",
                    style = MaterialTheme.typography.bodySmall)
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright")) { append("© OpenStreetMap contributors") }
                    append(" · ")
                    withLink(LinkAnnotation.Url("https://carto.com/attribution/")) { append("© CARTO") }
                    append("\n")
                    withLink(LinkAnnotation.Url("https://www.rainviewer.com/")) { append("Radar: RainViewer") }
                    append(" · ")
                    withLink(LinkAnnotation.Url("https://maplibre.org/")) { append("MapLibre") }
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    override fun onStart() { super.onStart(); map.view.onStart() }
    override fun onResume() { super.onResume(); map.view.onResume() }
    override fun onPause() { map.view.onPause(); super.onPause() }
    override fun onStop() { refreshJob?.cancel(); map.view.onStop(); super.onStop() }
    override fun onDestroy() { map.view.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); map.view.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        map.view.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }
}
