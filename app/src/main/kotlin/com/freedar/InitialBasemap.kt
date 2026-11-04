package com.freedar

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.tile.TileOperation

/** Observes only the style's initial sources, including parsing/glyph dependencies.
 * Kept until destruction so recovery can clear a terminal startup error without
 * treating later navigation or background revalidation as a new loading operation.
 */
internal class InitialBasemap(private val view: MapView, private val status: MutableStateFlow<String?>) {
    private data class Tile(val source: String, val x: Int, val y: Int, val z: Int, val wrap: Int, val overscaledZ: Int)
    private enum class State { PENDING, READY, FAILED }
    private val tiles = mutableMapOf<Tile, State>()
    private var sources: Set<String>? = null
    private val ready = MutableStateFlow(false)
    private val tileListener = MapView.OnTileActionListener { operation, x, y, z, wrap, overscaledZ, source ->
        // Before style readiness, retain events until source identities are known.
        if (sources == null || source in sources.orEmpty()) {
            val tile = Tile(source, x, y, z, wrap, overscaledZ)
            when (operation) {
                TileOperation.RequestedFromCache, TileOperation.RequestedFromNetwork, TileOperation.StartParse -> {
                    // Revalidation does not make an already usable tile unavailable.
                    if (tiles[tile] != State.READY) tiles[tile] = State.PENDING
                }
                TileOperation.EndParse -> tiles[tile] = State.READY
                TileOperation.Cancelled -> tiles.remove(tile)
                TileOperation.Error -> if (tiles[tile] != State.READY) {
                    tiles[tile] = State.FAILED
                    if (sources != null && !ready.value) status.value = "Base map imagery unavailable."
                }
                else -> Unit
            }
        }
    }
    private val rendered = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
        sources?.let { baseSources ->
            if (!ready.value) {
                val failed = tiles.values.any { it == State.FAILED }
                val pending = tiles.values.any { it == State.PENDING }
                val parsedSources = tiles.filterValues { it == State.READY }.keys.map { it.source }.toSet()
                // The full-frame flag includes radar/coverage. Parsed basemap sources
                // plus a rendered frame can finish independently of those requests.
                if (!failed && (fully || (!pending && parsedSources.containsAll(baseSources)))) {
                    ready.value = true
                    status.value = null
                    close()
                } else if (failed) status.value = "Base map imagery unavailable."
            }
        }
    }

    init {
        view.addOnTileActionListener(tileListener)
        view.addOnDidFinishRenderingFrameListener(rendered)
    }

    fun styleLoaded(style: Style) {
        sources = style.sources.map { it.id }.toSet()
        tiles.keys.removeAll { it.source !in sources.orEmpty() }
    }

    suspend fun awaitReady() {
        val settled = withTimeoutOrNull(30_000) {
            kotlinx.coroutines.flow.combine(ready, status) { usable, error -> usable || error != null }.first { it }
        }
        if (settled == null) status.value = "Base map imagery timed out."
        if (!ready.value) throw IOException(status.value)
    }

    fun close() {
        view.removeOnTileActionListener(tileListener)
        view.removeOnDidFinishRenderingFrameListener(rendered)
        tiles.clear()
    }
}
