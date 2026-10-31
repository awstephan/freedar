package com.freedar.radar

import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Application-lifetime, memory-only reuse. No speculative requests or disk persistence. */
class RadarTileRequests(
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val isRadarTile: (HttpUrl) -> Boolean = { it.encodedPath.endsWith(".png") },
) : Interceptor {
    private data class Tile(val bytes: ByteArray, val type: MediaType?, val loaded: Long)
    private val tiles = LinkedHashMap<String, Tile>(16, 0.75f, true)
    private var bytes = 0L
    private var blockedUntil = 0L
    private val requests = ArrayDeque<Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isRadarTile(request.url)) return chain.proceed(request)
        val key = request.url.toString()
        synchronized(this) {
            val tile = tiles[key]
            if (tile != null && nowMillis() - tile.loaded < 15 * 60_000) {
                return cached(request, tile)
            }
            if (tile != null) { tiles.remove(key); bytes -= tile.bytes.size }
            val now = nowMillis()
            while (requests.isNotEmpty() && requests.first() <= now - 60_000) requests.removeFirst()
            if (requests.size >= 90) blockedUntil = maxOf(blockedUntil, requests.first() + 60_000)
            if (nowMillis() < blockedUntil) return Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(429).message("Radar cooling down; try again later")
                .header("Retry-After", ((blockedUntil - nowMillis() + 999) / 1000).toString())
                .body(ByteArray(0).toResponseBody()).build()
            requests.addLast(now)
        }
        val response = chain.proceed(request)
        if (response.code == 429) synchronized(this) {
            val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 3600) ?: 60
            blockedUntil = maxOf(blockedUntil, nowMillis() + seconds * 1000)
        }
        val body = response.body ?: return response
        if (!response.isSuccessful) return response
        // A tile larger than this cap is rendered but never retained in our memory cache.
        val sample = response.peekBody(512 * 1024 + 1L)
        if (sample.contentLength() > 512 * 1024) return response
        val tile = Tile(sample.bytes(), body.contentType(), nowMillis())
        synchronized(this) {
            if (tile.bytes.size <= maxBytes) {
                tiles.put(key, tile)?.let { bytes -= it.bytes.size }
                bytes += tile.bytes.size
                while (bytes > maxBytes) {
                    val entry = tiles.entries.iterator()
                    bytes -= entry.next().value.bytes.size
                    entry.remove()
                }
            }
        }
        return response
    }

    private fun cached(request: Request, tile: Tile) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(200).message("In-session radar tile")
        .header("Cache-Control", "no-store").body(tile.bytes.toResponseBody(tile.type)).build()
}
