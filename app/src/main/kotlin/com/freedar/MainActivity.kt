package com.freedar

import android.os.Bundle
import android.os.SystemClock
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freedar.radar.LatestRadar
import com.freedar.radar.RadarState
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import com.freedar.radar.RainViewer
import com.freedar.radar.TimelineMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class MainActivity : ComponentActivity() {
    private var permissionResult: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val callback = permissionResult
        permissionResult = null
        callback?.invoke(grants.values.any { it })
    }
    protected open fun positionSource(): ForegroundPositionSource = AndroidPositionSource(this) { result ->
        if (permissionResult != null) result(false)
        else {
            permissionResult = result
            permissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    private lateinit var positioning: OneShotPositioning
    /** Shared entry point for map gestures and search-selection navigation. */
    fun navigationStarted() { positioning.navigationStarted() }
    protected open fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    protected open fun radarProvider(): RadarProvider = (application as FreedarApplication).let {
        RetainedRadarProvider(RainViewer(it.httpClient), it.radarArchive)
    }
    protected open fun baseStyleUrl(): String? = if (BuildConfig.CARTO_API_KEY.isBlank()) null else
        "https://basemaps.cartocdn.com/gl/${if (darkTheme) "dark-matter" else "positron"}-gl-style/style.json"
    private val darkTheme get() = appearance.dark(resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    private lateinit var map: RadarMap
    private lateinit var radar: LatestRadar
    private lateinit var appearance: AppearanceSettings
    private lateinit var rememberedView: RememberedView
    private var cameraReady = false
    private var baseMessage by mutableStateOf<String?>("Loading base map…")
    private var styleReady by mutableStateOf(false)
    private var refreshJob: Job? = null
    private var timelineJob: Job? = null
    private var restored = false
    private var recreationState: RadarState? = null
    private var stateBeforeStop: RadarState? = null
    private var returning = true
    private val controls = ControlsPresentation()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateConnectivity()
        override fun onLost(network: Network) = updateConnectivity()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateConnectivity()
    }

    private fun updateConnectivity() {
        runOnUiThread {
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            radar.connectivity(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appearance = AppearanceSettings(this)
        rememberedView = RememberedView(this)
        map = RadarMap(this)
        map.view.onCreate(savedInstanceState)
        val session = (application as FreedarApplication).processSession
        restored = savedInstanceState?.getString("processSession") == session
        val timelineState = savedInstanceState?.takeIf { restored }
        map.view.getMapAsync { native ->
            if (!restored) {
                val camera = rememberedView.read()
                if (camera != null) native.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(camera))
                else map.overview(native)
            }
            cameraReady = true
            native.addOnCameraIdleListener { rememberedView.save(native.cameraPosition) }
        }
        val selection = timelineState?.getString("radarPath")?.let {
            RadarFrame(Instant.ofEpochSecond(savedInstanceState.getLong("radarTime")), it)
        }
        val mode = timelineState?.getString("radarMode")?.let { name ->
            TimelineMode.entries.firstOrNull { it.name == name }
        } ?: TimelineMode.FOLLOWING
        radar = LatestRadar(radarProvider(), Clock.systemUTC(), map,
            monotonicMillis = ::elapsedMillis,
            restoredSelection = selection, restoredMode = mode,
            restoredAwaySince = timelineState?.takeIf { it.containsKey("radarAway") }?.getLong("radarAway"))
        positioning = OneShotPositioning(positionSource()) { position ->
            map.centerLocal(position)
            timeline { radar.latest() }
        }
        map.onNavigationStarted = ::navigationStarted
        setContent {
            MaterialTheme(colorScheme = if (darkTheme) androidx.compose.material3.darkColorScheme()
                else androidx.compose.material3.lightColorScheme()) {
                val opacity = appearance.opacity
                val speed = appearance.speed
                androidx.compose.runtime.SideEffect {
                    map.opacity(opacity)
                    radar.speed = speed
                }
                val state by radar.state.collectAsState()
                val tileStatus by map.status.collectAsState()
                val coverageStatus by map.coverageStatus.collectAsState()
                val positionState by positioning.state.collectAsState()
                MapChrome(controls,
                    appearance = appearance,
                    changeTheme = { theme ->
                        if (appearance.theme != theme) {
                            appearance.theme(theme)
                            // Reuse the tested camera/timeline/overlay restoration path. This also
                            // disposes coverage and pending native work before replacing the style.
                            recreate()
                        }
                    },
                    locate = { if (positionState.waiting) positioning.cancel() else positioning.locate() },
                    locating = positionState.waiting,
                    locateEnabled = styleReady,
                    selectPlace = { place ->
                        navigationStarted()
                        map.centerLocal(place.position)
                        timeline { radar.latest() }
                    },
                    map = { AndroidView(factory = { map.view }, modifier = Modifier.fillMaxSize()) },
                    status = {
                        RadarStatus(state, baseMessage, listOfNotNull(tileStatus, coverageStatus.message).joinToString("\n").ifEmpty { null }, positionState.message) {
                            controls.reveal()
                            refresh(retryDisplayed = true)
                        }
                    },
                    playback = { PlaybackStrip(state) },
                    credits = { Credits() },
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                refresh()
                var elapsed = 0
                try {
                    while (true) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try { (application as FreedarApplication).imagery.prune() }
                            catch (_: java.io.IOException) { /* Failed entries cannot be served past expiry. */ }
                        }
                        radar.updateAge()
                        delay(30_000)
                        elapsed += 30
                        if (elapsed >= 300) { elapsed = 0; refresh() }
                    }
                } finally {
                    radar.background()
                    timelineJob?.cancel()
                    refreshJob?.cancel()
                }
            }
        }
    }

    private fun timeline(action: suspend () -> Unit) {
        controls.reveal()
        timelineJob?.cancel()
        timelineJob = lifecycleScope.launch { action() }
    }

    @Composable private fun PlaybackStrip(state: RadarState) {
        val frames = state.frames
        if (frames.isEmpty()) return
        val span = (frames.size - 1).coerceAtLeast(1).toFloat()
        val selected = state.selection ?: state.frame ?: frames.last()
        val selectedIndex = frames.indexOfFirst { it.tileTemplate == selected.tileTemplate }.coerceAtLeast(0)
        val gapColor = MaterialTheme.colorScheme.error
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        controls.reveal()
                        if (state.mode == TimelineMode.PLAYING) { radar.pause(); timelineJob?.cancel() }
                        else timeline { radar.play() }
                    }) { Text(if (state.mode == TimelineMode.PLAYING) "Pause" else "Play") }
                    Text(if (state.mode == TimelineMode.FOLLOWING) "Following latest" else
                        if (state.mode == TimelineMode.PLAYING) "${appearance.speed.fps} fps" else "Selected frame",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { timeline { radar.latest() } }) { Text("Latest") }
                }
                Slider(
                    value = selectedIndex.toFloat(),
                    onValueChange = { offset ->
                        val frame = frames[offset.roundToInt().coerceIn(frames.indices)]
                        timeline { radar.select(frame) }
                    },
                    valueRange = 0f..(frames.size - 1).coerceAtLeast(1).toFloat(),
                    steps = (frames.size - 2).coerceAtLeast(0),
                    enabled = frames.size > 1,
                    modifier = Modifier.semantics {
                        contentDescription = "Radar history; scrubbing pauses playback"
                        stateDescription = "Frame ${selectedIndex + 1} of ${frames.size}, ${frameLabel(selected)}"
                    },
                )
                if (state.unavailable.isNotEmpty() || state.gaps.isNotEmpty()) {
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        state.gaps.forEach { (from, to) ->
                            drawLine(gapColor,
                                Offset(frames.indexOf(from) / span * size.width, size.height / 2),
                                Offset(frames.indexOf(to) / span * size.width, size.height / 2), size.height)
                        }
                        state.unavailable.forEach {
                            drawCircle(gapColor, 3.dp.toPx(),
                                Offset(frames.indexOf(it) / span * size.width, size.height / 2))
                        }
                    }
                    Text("Timeline gaps · ${state.unavailable.size} unavailable frames · ${state.gaps.size} missing intervals",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun refresh(retryDisplayed: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            if (!styleReady) {
                baseMessage = "Loading base map…"
                try {
                    val key = BuildConfig.CARTO_API_KEY
                    map.loadStyle(baseStyleUrl(), darkTheme)
                    styleReady = true
                    baseMessage = if (key.isBlank()) "Base map needs the developer's CARTO key. See build instructions." else null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    baseMessage = "Base map unavailable. Check connection and Retry."
                    return@launch
                }
            }
            if (returning) {
                val resume = radar.foreground()
                returning = false
                if (resume) timeline { radar.play(resume = true) }
            } else radar.refresh(retryDisplayed)
            radar.state.value.frames.lastOrNull()?.let {
                val host = it.tileTemplate.split('/').take(3).joinToString("/")
                map.showCoverage("$host/v2/coverage/0/256/{z}/{x}/{y}/0/0_0.png")
            }
        }
    }

    @Composable private fun RadarStatus(state: RadarState, base: String?, tiles: String?, location: String?, retry: () -> Unit) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val frame = state.frame
                    if (frame != null) {
                        Text("Frame time · ${frameLabel(frame)}", style = MaterialTheme.typography.bodyMedium)
                        Text("${state.ageSeconds / 60} min old",
                            style = MaterialTheme.typography.bodySmall)
                    } else Text(if (state.loading) "Loading radar…" else "Radar unavailable")
                    val notices = listOfNotNull(
                        "Stale feed".takeIf { state.stale },
                        "Offline".takeIf { state.offline },
                        "Saved radar".takeIf { state.retainedHistory },
                        "Refresh failed".takeIf { state.error != null },
                        "Updating".takeIf { state.loading && frame != null },
                        "Base map notice".takeIf { base != null },
                        "Imagery / coverage notice".takeIf { tiles != null },
                        "Location notice".takeIf { location != null },
                    )
                    if (notices.isNotEmpty()) Text(notices.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    // The frame and active notice summary stay anchored even when verbose
                    // provider diagnostics need scrolling at large accessibility text sizes.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        location?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
                        state.newestFrameTime?.let {
                            Text("Newest feed frame · ${state.newestAgeSeconds / 60} min old", style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.stale) Text("Stale radar feed", style = MaterialTheme.typography.bodySmall)
                        if (state.offline) Text("Offline · retained imagery is not live", style = MaterialTheme.typography.bodySmall)
                        if (state.retainedHistory) Text("Saved radar · original frame time, not live data", style = MaterialTheme.typography.bodySmall)
                        if (state.loading && frame != null) Text("Updating radar…", style = MaterialTheme.typography.bodySmall)
                        listOfNotNull(base, state.error, tiles).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
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
                Text("Zoom adds no radar detail. Shaded: outside static footprint. Elsewhere: live coverage unknown. " +
                    "Blank radar does not mean clear weather.",
                    style = MaterialTheme.typography.bodySmall)
                Text(buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright")) { append("© OpenStreetMap contributors") }
                    append(" · ")
                    withLink(LinkAnnotation.Url("https://carto.com/attribution/")) { append("© CARTO") }
                    append("\n")
                    withLink(LinkAnnotation.Url("https://www.rainviewer.com/")) { append("Radar: RainViewer") }
                    append(" · ")
                    withLink(LinkAnnotation.Url("https://maplibre.org/")) { append("MapLibre") }
                    append(" · ")
                    withLink(LinkAnnotation.Url("https://www.geonames.org/")) { append("Places: GeoNames") }
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun frameLabel(frame: RadarFrame): String {
        val date = Date.from(frame.time)
        val time = android.text.format.DateFormat.getTimeFormat(this).format(date)
        val day = android.text.format.DateFormat.getMediumDateFormat(this).format(date)
        val zone = DateTimeFormatter.ofPattern("z").withZone(ZoneId.systemDefault()).format(frame.time)
        return "$day, $time $zone"
    }

    override fun onStart() {
        super.onStart(); map.view.onStart()
        controls.hold(ControlsHold.INTERACTION, false)
        controls.hold(ControlsHold.KEYBOARD, false)
        controls.reveal()
        map.coverageForeground(true)
        returning = true
        connectivity.registerDefaultNetworkCallback(networkCallback)
        updateConnectivity()
    }
    override fun onResume() { super.onResume(); map.view.onResume() }
    override fun onPause() {
        if (cameraReady) map.view.getMapAsync { rememberedView.save(it.cameraPosition) }
        // Capture before API-29+ lifecycle pre-stop callbacks can pause playback.
        stateBeforeStop = radar.state.value
        map.view.onPause(); super.onPause()
    }
    override fun onStop() {
        positioning.background()
        if (isChangingConfigurations) recreationState = stateBeforeStop ?: radar.state.value
        radar.background(); timelineJob?.cancel(); refreshJob?.cancel()
        map.coverageForeground(false)
        connectivity.unregisterNetworkCallback(networkCallback)
        map.view.onStop(); super.onStop()
    }
    override fun onDestroy() { map.stopCoverage(); map.view.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); map.view.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("processSession", (application as FreedarApplication).processSession)
        map.view.onSaveInstanceState(outState)
        val state = recreationState ?: if (isChangingConfigurations) stateBeforeStop ?: radar.state.value
            else radar.state.value
        (state.selection ?: state.frame)?.let {
            outState.putString("radarPath", it.tileTemplate)
            outState.putLong("radarTime", it.time.epochSecond)
        }
        outState.putString("radarMode", state.mode.name)
        // API 26/27 may save before onStop. Persist a timestamp even in that order.
        outState.putLong("radarAway", radar.backgroundTime ?: elapsedMillis())
        radar.backgroundTime?.let {
            outState.putString("radarMode", radar.backgroundMode.name)
        }
        super.onSaveInstanceState(outState)
    }
}
