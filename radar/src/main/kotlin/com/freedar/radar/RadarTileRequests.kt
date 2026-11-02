package com.freedar.radar

import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.InterruptedIOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Application-lifetime, memory-only reuse. No speculative requests or disk persistence. */
class RadarTileRequests(
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val isRadarTile: (HttpUrl) -> Boolean = { it.encodedPath.endsWith(".png") },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
) : Interceptor {
    private data class Tile(val bytes: ByteArray, val type: MediaType?, val loaded: Long)
    private val tiles = LinkedHashMap<String, Tile>(16, 0.75f, true)
    private var bytes = 0L
    private var blockedUntil = 0L
    private var nextRequestAt = 0L

    val cooldownRemainingMillis: Long
        get() = synchronized(this) { (blockedUntil - nowMillis()).coerceAtLeast(0) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isRadarTile(request.url)) return chain.proceed(request)
        val key = request.url.toString()
        while (true) {
            if (chain.call().isCanceled()) throw InterruptedIOException("Radar request cancelled")
            val wait = synchronized(this) {
                val tile = tiles[key]
                if (tile != null && nowMillis() - tile.loaded < 15 * 60_000) {
                    return cached(request, tile)
                }
                if (tile != null) { tiles.remove(key); bytes -= tile.bytes.size }
                val now = nowMillis()
                if (now < blockedUntil) return Response.Builder().request(request)
                    .protocol(Protocol.HTTP_1_1).code(429).message("Radar cooling down; try again later")
                    .header("Retry-After", ((blockedUntil - now + 999) / 1000).toString())
                    .body(ByteArray(0).toResponseBody()).build()
                // At most 80 new tiles/minute, spread out rather than a burst then rejection.
                val remaining = (nextRequestAt - now).coerceAtLeast(0)
                if (remaining == 0L) nextRequestAt = now + 750
                remaining
            }
            if (wait == 0L) break
            try { pause(minOf(wait, 100)) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Radar pacing interrupted")
            }
        }
        val response = chain.proceed(request)
        if (response.code == 429) synchronized(this) {
            val header = response.header("Retry-After")
            val duration = header?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.toLongOrNull()?.coerceIn(1, 86_400)?.times(1000) ?: runCatching {
                ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - wallClockMillis()
            }.getOrDefault(60_000).coerceAtLeast(1000)
            blockedUntil = maxOf(blockedUntil, nowMillis() + duration)
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
