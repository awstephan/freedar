package com.freedar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.rasterFadeDuration
import org.maplibre.android.style.sources.ImageSource

enum class CoverageStatus(val message: String?) {
    UNKNOWN("Coverage footprint not loaded; availability unknown."),
    LOADING("Loading coverage footprint; unshaded areas may be unknown."),
    READY(null),
    UNAVAILABLE("Coverage footprint unavailable in some areas; availability unknown."),
}

/** Fetch independently; only decoded images enter the native style. A coverage outage
 * must never participate in the native radar presentation-ready gate. */
internal class CoverageFootprint(
    private val map: MapLibreMap,
    private val style: Style,
    private val client: OkHttpClient,
) {
    private val mutableStatus = MutableStateFlow(CoverageStatus.UNKNOWN)
    val status = mutableStatus.asStateFlow()
    private var template: String? = null
    private var job: Job? = null
    private var active = true
    private val displayed = mutableSetOf<Tile>()
    private var wanted = emptySet<Tile>()

    init { map.addOnCameraIdleListener { update() } }

    fun show(template: String) {
        if (this.template != template) {
            displayed.toList().forEach(::remove)
            wanted = emptySet()
            job?.cancel()
        }
        this.template = template
        update(retry = true)
    }

    fun foreground(active: Boolean) {
        this.active = active
        if (active) update(retry = true) else { job?.cancel(); job = null }
    }

    private fun update(retry: Boolean = false) {
        val url = template ?: return
        if (!active) return
        val tiles = viewportTiles()
        if (tiles == wanted && (job?.isActive == true || (!retry && mutableStatus.value == CoverageStatus.UNAVAILABLE))) return
        wanted = tiles
        job?.cancel()
        displayed.filterNot { it in tiles }.toList().forEach(::remove)
        if (displayed.containsAll(tiles)) { mutableStatus.value = CoverageStatus.READY; return }
        mutableStatus.value = CoverageStatus.LOADING
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            var failed = false
            for (tile in tiles - displayed) {
                try {
                    val bitmap = fetch(url.replace("{z}", "${tile.z}").replace("{x}", "${tile.x}").replace("{y}", "${tile.y}"))
                    ensureActive()
                    style.addSource(ImageSource(tile.id, tile.bounds(), bitmap))
                    val layer = RasterLayer(tile.id, tile.id).withProperties(rasterOpacity(0.22f), rasterFadeDuration(0f))
                    val labels = style.layers.firstOrNull { it is SymbolLayer }
                    if (labels == null) style.addLayer(layer) else style.addLayerBelow(layer, labels.id)
                    displayed.add(tile)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
            mutableStatus.value = if (failed) CoverageStatus.UNAVAILABLE else CoverageStatus.READY
        }
    }

    private suspend fun fetch(url: String): Bitmap = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val bitmap = response.use {
                        check(it.isSuccessful)
                        val bytes = requireNotNull(it.body).bytes()
                        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    }
                    if (continuation.isActive) continuation.resume(bitmap) else bitmap.recycle()
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun remove(tile: Tile) {
        style.removeLayer(tile.id)
        style.removeSource(tile.id)
        displayed.remove(tile)
    }

    /** Bound viewport work, including low zoom and antimeridian views, to 16 tiles. */
    private fun viewportTiles(): Set<Tile> {
        val bounds = map.projection.visibleRegion.latLngBounds
        var zoom = floor(map.cameraPosition.zoom).toInt().coerceIn(0, 7)
        while (true) {
            val n = 1 shl zoom
            fun x(lon: Double) = floor((lon + 180) / 360 * n).toInt()
            fun y(lat: Double): Int {
                val radians = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
                return floor((1 - ln(tan(radians) + 1 / cos(radians)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
            }
            val west = x(bounds.longitudeWest)
            var east = x(bounds.longitudeEast)
            if (east < west) east += n
            east = east.coerceAtMost(west + n - 1)
            val north = y(bounds.latitudeNorth)
            val south = y(bounds.latitudeSouth)
            if ((east - west + 1) * (south - north + 1) <= 16 || zoom == 0) {
                return (west..east).flatMap { column -> (north..south).map { row ->
                    Tile(zoom, Math.floorMod(column, n), row)
                } }.toSet()
            }
            zoom--
        }
    }

    private data class Tile(val z: Int, val x: Int, val y: Int) {
        val id get() = "coverage-$z-$x-$y"
        fun bounds(): LatLngQuad {
            val n = (1 shl z).toDouble()
            fun latitude(row: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * row / n))))
            val west = x / n * 360 - 180
            val east = (x + 1) / n * 360 - 180
            return LatLngQuad(LatLng(latitude(y), west), LatLng(latitude(y), east),
                LatLng(latitude(y + 1), east), LatLng(latitude(y + 1), west))
        }
    }
}
