package com.freedar

import android.content.Context
import com.freedar.radar.RadarDisplay
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarCoolingDown
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** Main-thread native boundary. Owns one visible frame and at most one pending replacement. */
class RadarMap(context: Context) : RadarDisplay {
    private val client = (context.applicationContext as FreedarApplication).httpClient
    private val tileRequests = (context.applicationContext as FreedarApplication).radarTileRequests
    init { MapLibre.getInstance(context) }
    val view = MapView(context)
    lateinit var nativeMap: MapLibreMap
        private set
    private lateinit var style: Style
    private var initialBasemap: InitialBasemap? = null
    private val mutableBasemapStatus = MutableStateFlow<String?>(null)
    val basemapStatus = mutableBasemapStatus.asStateFlow()
    private var visibleId: String? = null
    private var opacity = AppearanceSettings.DEFAULT_OPACITY
    private var presentingId: String? = null
    var currentLocation: LocalPosition? = null
        private set
    var searchedLocation: LocalPosition? = null
        private set

    fun showCurrentLocation(position: LocalPosition) {
        currentLocation = position
        updateLocationMarkers()
    }

    fun showSearchedLocation(position: LocalPosition) {
        searchedLocation = position
        updateLocationMarkers()
    }

    private fun updateLocationMarkers() {
        if (!::style.isInitialized || !style.isFullyLoaded || view.isDestroyed) return
        fun marker(id: String, position: LocalPosition?, color: String, radius: Float) {
            val features = FeatureCollection.fromFeatures(position?.let {
                arrayOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
            } ?: emptyArray())
            val source = style.getSourceAs<GeoJsonSource>(id)
            if (source != null) source.setGeoJson(features) else {
                style.addSource(GeoJsonSource(id, features))
                style.addLayer(CircleLayer(id, id).withProperties(
                    circleColor(color), circleRadius(radius), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
            }
        }
        marker(SEARCHED_LOCATION, searchedLocation, "#A855F7", 6f)
        marker(CURRENT_LOCATION, currentLocation, "#2196F3", 6f)
    }

    fun opacity(value: Float) {
        opacity = value
        if (!::style.isInitialized || view.isDestroyed) return
        // During a replacement only the incoming layer is exposed; don't unhide the old one.
        (presentingId ?: visibleId)?.let { style.getLayer(it)?.setProperties(rasterOpacity(value)) }
    }
    private var sequence = 0
    private val mutableStatus = MutableStateFlow<String?>(null)
    val status = mutableStatus.asStateFlow()
    private val mutableViewportCooldown = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val viewportCooldown = mutableViewportCooldown.asSharedFlow()
    private val mutableCoverageStatus = MutableStateFlow(CoverageStatus.UNKNOWN)
    val coverageStatus = mutableCoverageStatus.asStateFlow()
    private var coverage: CoverageFootprint? = null
    private var coverageObserver: Job? = null
    var onNavigationStarted: () -> Unit = {}
    private val navigationListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onNavigationStarted()
    }

    init {
        view.getMapAsync { it.addOnCameraMoveStartedListener(navigationListener) }
        view.addOnTileActionListener { operation, _, _, _, _, _, source ->
            if (source == visibleId && operation == TileOperation.Error) {
                if (tileRequests.cooldownRemainingMillis > 0) {
                    mutableViewportCooldown.tryEmit(Unit)
                } else mutableStatus.value = "Some radar tiles are unavailable. Blank areas do not mean clear weather."
            }
        }
    }

    /** Static footprint only: transparent means potentially covered, never live health. */
    fun showCoverage(template: String) {
        if (coverage == null) {
            coverage = CoverageFootprint(nativeMap, style, client)
            coverageObserver = CoroutineScope(Dispatchers.Main.immediate).launch {
                requireNotNull(coverage).status.collect { mutableCoverageStatus.value = it }
            }
        }
        coverage?.show(template)
    }

    fun coverageForeground(active: Boolean) { coverage?.foreground(active) }
    fun stopCoverage() { coverage?.foreground(false); coverageObserver?.cancel() }
    fun dispose() { stopCoverage(); initialBasemap?.close() }

    suspend fun loadStyle(url: String?, dark: Boolean = false) {
        initialBasemap?.close()
        mutableBasemapStatus.value = null
        initialBasemap = InitialBasemap(view, mutableBasemapStatus)
        nativeMap = suspendCancellableCoroutine { continuation ->
            view.getMapAsync { if (continuation.isActive) continuation.resume(it) }
        }
        nativeMap.uiSettings.apply {
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = false
            isLogoEnabled = false
            // Individually linked credits are supplied by the Compose Attributions panel.
            isAttributionEnabled = false
        }
        nativeMap.removeOnCameraMoveStartedListener(navigationListener)
        nativeMap.addOnCameraMoveStartedListener(navigationListener)
        nativeMap.setMaxZoomPreference(18.0)
        val loaded = CompletableDeferred<Style>()
        val failure = MapView.OnDidFailLoadingMapListener {
            loaded.completeExceptionally(IOException("Base map unavailable"))
        }
        view.addOnDidFailLoadingMapListener(failure)
        try {
            val builder = if (url == null) Style.Builder().fromJson(
                if (dark) EMPTY_STYLE.replace("#e9eeec", "#333333") else EMPTY_STYLE
            ) else Style.Builder().fromUri(url)
            nativeMap.setStyle(builder) {
                initialBasemap?.styleLoaded(it)
                loaded.complete(it)
            }
            style = withTimeoutOrNull(30_000) { loaded.await() } ?: throw IOException("Base map timed out")
            if (dark) {
                style.layers.filterIsInstance<org.maplibre.android.style.layers.BackgroundLayer>().forEach {
                    it.setProperties(org.maplibre.android.style.layers.PropertyFactory.backgroundColor("#333333"))
                }
                style.layers.filterIsInstance<org.maplibre.android.style.layers.FillLayer>().forEach {
                    val color = when (it.id) {
                        "landcover", "landuse", "landuse_residential" -> "#333333"
                        "park_national_park", "park_nature_reserve" -> "#303830"
                        "water" -> "#222222"
                        else -> null
                    }
                    if (color != null) it.setProperties(org.maplibre.android.style.layers.PropertyFactory.fillColor(color))
                }
                // Dark Matter's low-contrast road fills disappear against strong red echoes.
                // Keep its dark casings and brighten transport fills above the radar.
                style.layers.filterIsInstance<org.maplibre.android.style.layers.LineLayer>().filter {
                    it.id.isCartoTransportLayer() && !it.id.contains("case")
                }.forEach { it.setProperties(org.maplibre.android.style.layers.PropertyFactory.lineColor("#c3c9d1")) }
            }
            visibleId = null
            updateLocationMarkers()
        } finally {
            view.removeOnDidFailLoadingMapListener(failure)
        }
    }

    /** Initial base imagery only; radar and coverage can load concurrently. */
    suspend fun awaitInitialBasemap() {
        nativeMap.triggerRepaint()
        requireNotNull(initialBasemap).awaitReady()
    }

    /** Zoom 9 frames a local region; ground span depends on latitude and viewport. */
    fun centerLocal(position: LocalPosition) {
        view.getMapAsync { map ->
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(position.latitude, position.longitude), 9.0))
        }
    }

    fun overview(map: MapLibreMap = nativeMap) {
        // First-open overview of the contiguous USA; exploration remains worldwide.
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.from(49.5, -66.0, 24.0, -125.0), 36,
        ))
    }

    override suspend fun show(frame: RadarFrame) {
        load(frame, prepareOnly = false) {}
    }

    override suspend fun showWhenReady(frame: RadarFrame, preparationChanged: (Boolean) -> Unit,
        awaitPresentationTime: suspend () -> Unit) {
        load(frame, prepareOnly = false, preparationChanged, awaitPresentationTime)
    }

    override suspend fun prepare(frame: RadarFrame) {
        load(frame, prepareOnly = true) {}
    }

    private suspend fun load(frame: RadarFrame, prepareOnly: Boolean,
        preparationChanged: (Boolean) -> Unit = {}, awaitPresentationTime: suspend () -> Unit) {
        val id = "radar-${++sequence}"
        val ready = CompletableDeferred<Unit>()
        val presented = CompletableDeferred<Unit>()
        var presenting = false
        var parsed = false
        var tileFailure: IOException? = null
        var viewport = 0L
        var readyViewport = -1L
        var viewportReady: CompletableDeferred<Unit>? = null
        data class Tile(val x: Int, val y: Int, val z: Int, val wrap: Int, val overscaledZ: Int)
        val pendingTiles = mutableSetOf<Tile>()
        val parsedTiles = mutableSetOf<Tile>()
        val moved = MapLibreMap.OnCameraMoveListener { viewport++ }
        val tiles = MapView.OnTileActionListener { operation, x, y, z, wrap, overscaledZ, source ->
            if (source == id) {
                val tile = Tile(x, y, z, wrap, overscaledZ)
                when (operation) {
                    // Native also probes cached fallback ancestors without ever loading them;
                    // those lookups have no matching completion/cancellation event.
                    TileOperation.RequestedFromNetwork, TileOperation.StartParse -> {
                        if (tile !in parsedTiles) {
                            pendingTiles.add(tile)
                            if (!prepareOnly && !presenting && tileFailure == null) preparationChanged(true)
                        }
                    }
                    TileOperation.EndParse -> {
                        parsed = true
                        pendingTiles.remove(tile)
                        parsedTiles.add(tile)
                    }
                    TileOperation.Cancelled -> pendingTiles.remove(tile)
                    TileOperation.Error -> {
                        val remaining = tileRequests.cooldownRemainingMillis
                        val error = if (remaining > 0) RadarCoolingDown(remaining) else IOException("Radar tiles unavailable")
                        tileFailure = error
                        preparationChanged(false)
                        ready.completeExceptionally(error)
                        presented.completeExceptionally(error)
                        viewportReady?.completeExceptionally(error)
                    }
                    else -> Unit
                }
            }
        }
        val rendered = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            // Candidate-source readiness is independent of unrelated basemap/coverage work,
            // including new candidate tiles requested while the presentation gate is waiting.
            if (parsed && pendingTiles.isEmpty() && tileFailure == null) preparationChanged(false)
            if (fully && parsed) {
                readyViewport = viewport
                viewportReady?.complete(Unit)
                if (presenting) presented.complete(Unit) else ready.complete(Unit)
            }
        }
        view.addOnTileActionListener(tiles)
        view.addOnDidFinishRenderingFrameListener(rendered)
        nativeMap.addOnCameraMoveListener(moved)
        var committed = false
        try {
            val tileSet = TileSet("2.2.0", frame.tileTemplate).apply {
                minZoom = 0f
                maxZoom = 7f
            }
            style.addSource(RasterSource(id, tileSet, 256))
            // Load underneath the opaque base-map background, never blended into the
            // displayed radar. Both our fallback and CARTO Positron have that background.
            val loadingLayer = radarLayer(id)
            style.addLayerBelow(loadingLayer, style.layers.first().id)
            withTimeoutOrNull(30_000) { ready.await() } ?: throw IOException("Radar tiles timed out")
            if (prepareOnly) return // finally removes native resources; HTTP bytes remain in session memory.
            preparationChanged(false)
            awaitPresentationTime()
            tileFailure?.let { throw it }
            while (readyViewport != viewport) {
                val currentViewport = CompletableDeferred<Unit>()
                viewportReady = currentViewport
                withTimeoutOrNull(30_000) { currentViewport.await() }
                    ?: throw IOException("Radar viewport timed out")
            }
            tileFailure?.let { throw it }
            preparationChanged(false)
            style.removeLayer(id)
            val layer = radarLayer(id)
            val reference = style.radarReferenceLayer()
            if (reference == null) style.addLayer(layer) else style.addLayerBelow(layer, reference)
            visibleId?.let { style.getLayer(it)?.setProperties(rasterOpacity(0f)) }
            presentingId = id
            presenting = true
            withTimeoutOrNull(30_000) { presented.await() } ?: throw IOException("Radar presentation timed out")
            visibleId?.let { removeFrame(it) }
            visibleId = id
            mutableStatus.value = null
            committed = true
        } finally {
            preparationChanged(false)
            presentingId = null
            view.removeOnTileActionListener(tiles)
            view.removeOnDidFinishRenderingFrameListener(rendered)
            nativeMap.removeOnCameraMoveListener(moved)
            if (!committed) {
                removeFrame(id)
                if (!view.isDestroyed) visibleId?.let { style.getLayer(it)?.setProperties(rasterOpacity(opacity)) }
            }
        }
    }

    private fun radarLayer(id: String) = RasterLayer(id, id)
        .withProperties(rasterOpacity(opacity), rasterFadeDuration(0f))
        .apply { rasterOpacityTransition = TransitionOptions(0, 0) }

    private fun removeFrame(id: String) {
        if (view.isDestroyed) return
        style.removeLayer(id)
        style.removeSource(id)
    }

    companion object {
        internal const val CURRENT_LOCATION = "current-location"
        internal const val SEARCHED_LOCATION = "searched-location"
        private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[
            {"id":"background","type":"background","paint":{"background-color":"#e9eeec"}}]}"""
    }
}

/** CARTO's transport casings/fills and labels remain legible even at 100% radar opacity. */
internal fun Style.radarReferenceLayer(): String? = layers.firstOrNull {
    it is SymbolLayer || it.id.isCartoTransportLayer() || it.id == RadarMap.SEARCHED_LOCATION
}?.id

private fun String.isCartoTransportLayer() = startsWith("tunnel_") || startsWith("road_") || startsWith("bridge_")
