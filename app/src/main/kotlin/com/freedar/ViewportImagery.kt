package com.freedar

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.tile.TileOperation
import kotlin.math.*

/** Native tile events are source-scoped but not viewport-scoped. Project their footprints
 * into the current north-up camera before counting work; never count cache ancestor probes.
 * A rendered frame reconciles the batch of tile events, independently of other sources.
 */
internal class ViewportImagery(
    private val view: MapView,
    private val map: MapLibreMap,
    private val timedOut: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onReady: (String) -> Unit,
) {
    private data class Tile(val x: Int, val y: Int, val z: Int, val wrap: Int, val overscaled: Int)
    private enum class State { PENDING, READY, FAILED, TIMED_OUT }
    private class Source(val maxZoom: Int, val tileSize: Int?) {
        val tiles = mutableMapOf<Tile, State>()
        val started = mutableMapOf<Tile, Long>()
        var failureReported = false
        var deadline: Runnable? = null
    }
    private val sources = mutableMapOf<String, Source>()
    private var contributing = emptySet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val mutableLoading = MutableStateFlow(false)
    private val revision = MutableStateFlow(0L)
    private var active = true
    private var bounds = map.projection.visibleRegion.latLngBounds
    private var zoom = map.cameraPosition.zoom
    val loading = mutableLoading.asStateFlow()
    private val moved = MapLibreMap.OnCameraMoveListener {
        bounds = map.projection.visibleRegion.latLngBounds
        zoom = map.cameraPosition.zoom
        // Old-view work loses ownership immediately; new work is reconciled after rendering.
        update()
    }
    private val tiles = MapView.OnTileActionListener { operation, x, y, z, wrap, overscaled, id ->
        sources[id]?.let { source ->
            val tile = Tile(x, y, z, wrap, overscaled)
            when (operation) {
                // TileLoader probes cache on construction, not on revalidation. A new
                // native tile must not inherit readiness from an evicted incarnation.
                TileOperation.RequestedFromCache -> {
                    source.tiles.remove(tile)
                    source.started.remove(tile)
                }
                TileOperation.RequestedFromNetwork, TileOperation.StartParse -> {
                    if (source.tiles[tile] != State.READY && source.tiles[tile] != State.TIMED_OUT) {
                        source.tiles[tile] = State.PENDING
                        source.started.putIfAbsent(tile, SystemClock.uptimeMillis())
                    }
                }
                TileOperation.EndParse -> { source.tiles[tile] = State.READY; source.started.remove(tile) }
                TileOperation.Cancelled -> { source.tiles.remove(tile); source.started.remove(tile) }
                TileOperation.Error -> if (source.tiles[tile] != State.READY) {
                    source.tiles[tile] = State.FAILED
                    source.started.remove(tile)
                }
                else -> Unit
            }
            if (operation == TileOperation.Error || operation == TileOperation.Cancelled) {
                update()
                revision.value++
            }
        }
    }
    private val rendered = MapView.OnDidFinishRenderingFrameListener { _, _, _ ->
        bounds = map.projection.visibleRegion.latLngBounds
        zoom = map.cameraPosition.zoom
        update()
        revision.value++
    }

    init {
        view.addOnTileActionListener(tiles)
        view.addOnDidFinishRenderingFrameListener(rendered)
        map.addOnCameraMoveListener(moved)
    }

    fun add(id: String, maxZoom: Int = 22, tileSize: Int? = null) {
        sources[id] = Source(maxZoom, tileSize)
    }
    fun remove(id: String) {
        sources.remove(id)?.deadline?.let(handler::removeCallbacks)
        update()
    }
    fun contribute(ids: Set<String>) { contributing = ids; update() }

    fun foreground(value: Boolean) {
        active = value
        update()
    }

    private fun current(tile: Tile, source: Source): Boolean {
        if (source.tileSize == null) {
            if (tile.overscaled != floor(zoom).toInt()) return false
        } else {
            val target = floor(zoom + ln(512.0 / source.tileSize) / ln(2.0) + .5).toInt()
            if (tile.z != min(target, source.maxZoom)) return false
        }
        val n = 2.0.pow(tile.z)
        val west = tile.x / n * 360 - 180
        val east = (tile.x + 1) / n * 360 - 180
        fun latitude(y: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / n))))
        if (latitude(tile.y) < bounds.latitudeSouth || latitude(tile.y + 1) > bounds.latitudeNorth) return false
        // Bounds can cross the antimeridian or describe an unwrapped world copy.
        val start = bounds.longitudeWest
        var end = bounds.longitudeEast
        if (end < start) end += 360
        val shift = floor((start - west) / 360)
        return (0..1).any { offset ->
            val delta = (shift + offset) * 360
            east + delta > start && west + delta < end
        }
    }

    fun ready(id: String): Boolean {
        // Another render listener can synchronously move the camera before the camera
        // notification is dispatched. Never acknowledge the preceding view in that gap.
        bounds = map.projection.visibleRegion.latLngBounds
        zoom = map.cameraPosition.zoom
        val source = sources[id] ?: return false
        val current = source.tiles.filterKeys { current(it, source) }
        if (current.isEmpty() || current.values.any { it != State.READY }) return false
        // Require complete current-view coverage, not merely one overlapping parsed tile.
        // This also rejects a fully-rendered callback queued for the preceding camera.
        val z = current.keys.maxOf { it.z }
        val n = 1 shl z
        val ready = current.keys.filter { it.z == z }.map { it.x to it.y }.toSet()
        var east = bounds.longitudeEast
        if (east < bounds.longitudeWest) east += 360
        val westX = floor((bounds.longitudeWest + 180) / 360 * n).toInt()
        val eastX = ceil((east + 180) / 360 * n).toInt() - 1
        fun tileY(latitude: Double): Double {
            val radians = Math.toRadians(latitude.coerceIn(-85.05112878, 85.05112878))
            return (1 - asinh(tan(radians)) / PI) / 2 * n
        }
        val northY = floor(tileY(bounds.latitudeNorth)).toInt().coerceIn(0, n - 1)
        val southY = (ceil(tileY(bounds.latitudeSouth)).toInt() - 1).coerceIn(0, n - 1)
        return (westX..eastX).all { x ->
            (northY..southY).all { y -> (Math.floorMod(x, n) to y) in ready }
        }
    }

    fun failed(id: String): Boolean = sources[id]?.let { source ->
        source.tiles.any { (tile, state) -> state == State.FAILED && current(tile, source) }
    } ?: false

    suspend fun awaitReady(ids: Set<String>) {
        revision.first { ids.all { ready(it) || failed(it) || expired(it) } }
        if (ids.any { failed(it) || expired(it) }) throw java.io.IOException("Base map imagery unavailable.")
    }

    private fun expired(id: String) = sources[id]?.let { source ->
        source.tiles.any { (tile, state) -> state == State.TIMED_OUT && current(tile, source) }
    } ?: false

    private fun update() {
        var busy = false
        sources.forEach { (id, source) ->
            if (id in contributing && ready(id)) onReady(id)
            val failure = failed(id)
            if (id in contributing && !source.failureReported && failure) {
                onFailure(id)
            }
            source.failureReported = failure
            source.deadline?.let(handler::removeCallbacks)
            source.deadline = null
            val pending = active && id in contributing && !failure && !expired(id) &&
                source.tiles.any { (tile, state) -> state == State.PENDING && current(tile, source) }
            if (pending) {
                busy = true
                val pendingTiles = source.tiles.filter { (tile, state) -> state == State.PENDING && current(tile, source) }.keys
                val deadlineAt = pendingTiles.minOf { requireNotNull(source.started[it]) } + 30_000
                val deadline = Runnable {
                    pendingTiles.forEach { tile ->
                        if (source.tiles[tile] == State.PENDING) source.tiles[tile] = State.TIMED_OUT
                    }
                    source.deadline = null
                    timedOut(id)
                    update()
                    revision.value++
                }
                source.deadline = deadline
                handler.postAtTime(deadline, deadlineAt)
            }
        }
        mutableLoading.value = busy
    }

    fun close() {
        sources.values.forEach { it.deadline?.let(handler::removeCallbacks) }
        sources.clear()
        mutableLoading.value = false
        view.removeOnTileActionListener(tiles)
        view.removeOnDidFinishRenderingFrameListener(rendered)
        map.removeOnCameraMoveListener(moved)
    }
}
