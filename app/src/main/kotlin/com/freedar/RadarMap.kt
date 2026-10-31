package com.freedar

import android.content.Context
import com.freedar.radar.RadarDisplay
import com.freedar.radar.RadarFrame
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.tile.TileOperation

/** Main-thread native boundary. Owns one visible frame and at most one pending replacement. */
class RadarMap(context: Context) : RadarDisplay {
    init { MapLibre.getInstance(context) }
    val view = MapView(context)
    lateinit var nativeMap: MapLibreMap
        private set
    private lateinit var style: Style
    private var visibleId: String? = null
    private var sequence = 0
    private val mutableStatus = MutableStateFlow<String?>(null)
    val status = mutableStatus.asStateFlow()

    init {
        view.addOnTileActionListener { operation, _, _, _, _, _, source ->
            if (source == visibleId && operation == TileOperation.Error) {
                mutableStatus.value = "Some radar tiles are unavailable. Blank areas do not mean clear weather."
            }
        }
    }

    suspend fun loadStyle(url: String?) {
        nativeMap = suspendCancellableCoroutine { continuation ->
            view.getMapAsync { if (continuation.isActive) continuation.resume(it) }
        }
        nativeMap.uiSettings.apply {
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isCompassEnabled = false
            isLogoEnabled = false
            // Persistent, individually linked credits are supplied by the Compose overlay.
            isAttributionEnabled = false
        }
        nativeMap.setMaxZoomPreference(18.0)
        val loaded = CompletableDeferred<Style>()
        val failure = MapView.OnDidFailLoadingMapListener {
            loaded.completeExceptionally(IOException("Base map unavailable"))
        }
        view.addOnDidFailLoadingMapListener(failure)
        try {
            val builder = if (url == null) Style.Builder().fromJson(EMPTY_STYLE) else Style.Builder().fromUri(url)
            nativeMap.setStyle(builder) { loaded.complete(it) }
            style = withTimeoutOrNull(30_000) { loaded.await() } ?: throw IOException("Base map timed out")
            visibleId = null
        } finally {
            view.removeOnDidFailLoadingMapListener(failure)
        }
    }

    fun overview() {
        // Include the Aleutians across the antimeridian (172 E == -188 W), Hawaii,
        // northern Alaska and the eastern contiguous states. No navigation bounds.
        nativeMap.moveCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.from(72.0, -66.0, 18.0, -188.0), 36,
        ))
    }

    override suspend fun show(frame: RadarFrame) {
        val id = "radar-${++sequence}"
        val ready = CompletableDeferred<Unit>()
        val presented = CompletableDeferred<Unit>()
        var presenting = false
        var parsed = false
        val tiles = MapView.OnTileActionListener { operation, _, _, _, _, _, source ->
            if (source == id) when (operation) {
                TileOperation.EndParse -> parsed = true
                TileOperation.Error -> {
                    val error = IOException("Radar tiles unavailable")
                    ready.completeExceptionally(error)
                    presented.completeExceptionally(error)
                }
                else -> Unit
            }
        }
        val rendered = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && parsed) {
                if (presenting) presented.complete(Unit) else ready.complete(Unit)
            }
        }
        view.addOnTileActionListener(tiles)
        view.addOnDidFinishRenderingFrameListener(rendered)
        var committed = false
        try {
            val tileSet = TileSet("2.2.0", frame.tileTemplate).apply {
                minZoom = 0f
                maxZoom = 7f
            }
            style.addSource(RasterSource(id, tileSet, 256))
            // Nonzero opacity requests native tiles while the preceding frame stays visible.
            val layer = RasterLayer(id, id).withProperties(rasterOpacity(0.001f), rasterFadeDuration(0f))
            layer.rasterOpacityTransition = TransitionOptions(0, 0)
            val labels = style.layers.firstOrNull { it is SymbolLayer }
            if (labels == null) style.addLayer(layer) else style.addLayerBelow(layer, labels.id)
            withTimeoutOrNull(30_000) { ready.await() } ?: throw IOException("Radar tiles timed out")
            layer.setProperties(rasterOpacity(0.65f))
            visibleId?.let { style.getLayer(it)?.setProperties(rasterOpacity(0f)) }
            presenting = true
            withTimeoutOrNull(30_000) { presented.await() } ?: throw IOException("Radar presentation timed out")
            visibleId?.let { removeFrame(it) }
            visibleId = id
            mutableStatus.value = null
            committed = true
        } finally {
            view.removeOnTileActionListener(tiles)
            view.removeOnDidFinishRenderingFrameListener(rendered)
            if (!committed) {
                removeFrame(id)
                if (!view.isDestroyed) visibleId?.let { style.getLayer(it)?.setProperties(rasterOpacity(0.65f)) }
            }
        }
    }

    private fun removeFrame(id: String) {
        if (view.isDestroyed) return
        style.removeLayer(id)
        style.removeSource(id)
    }

    companion object {
        private const val EMPTY_STYLE = """{"version":8,"sources":{},"layers":[
            {"id":"background","type":"background","paint":{"background-color":"#e9eeec"}}]}"""
    }
}
