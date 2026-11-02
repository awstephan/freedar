package com.freedar.radar

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TileLoadingTest {
    @Test fun `missing malformed and negative retry after default to one minute`() {
        for (header in listOf(null, "invalid", "-1")) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).apply { if (header != null) setHeader("Retry-After", header) })
            val policy = RadarTileRequests(nowMillis = { 0 })
            val client = OkHttpClient.Builder().addInterceptor(policy).build()
            client.newCall(Request.Builder().url(server.url("/cold.png")).build()).execute().close()
            assertEquals(60_000L, policy.cooldownRemainingMillis)
        }
    }
    @Test fun `cached tiles bypass pacing and cancelled queued work never reaches server`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("warm"))
            var now = 0L
            var waits = 0
            lateinit var pending: okhttp3.Call
            val client = OkHttpClient.Builder().addInterceptor(RadarTileRequests(nowMillis = { now }, pause = {
                waits++
                pending.cancel()
                now += it
            })).build()
            fun request(path: String) = Request.Builder().url(server.url(path)).build()
            client.newCall(request("/warm.png")).execute().close()
            client.newCall(request("/warm.png")).execute().use { assertEquals(200, it.code) }
            assertEquals(0, waits)
            pending = client.newCall(request("/cancelled.png"))
            try { pending.execute().close(); fail("Cancelled pacing must stop") }
            catch (_: java.io.IOException) { }
            assertEquals(1, server.requestCount)
            assertEquals(1, waits)
        }
    }

    @Test fun `provider HTTP date retry after creates shared cooldown without new network calls`() {
        MockWebServer().use { server ->
            val wall = java.time.Instant.parse("2026-11-02T12:00:00Z")
            val retry = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(wall.plusSeconds(120).atZone(java.time.ZoneOffset.UTC))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", retry))
            var now = 0L
            val policy = RadarTileRequests(nowMillis = { now }, wallClockMillis = { wall.toEpochMilli() })
            val client = OkHttpClient.Builder().addInterceptor(policy).build()
            fun status() = client.newCall(Request.Builder().url(server.url("/cold.png")).build()).execute().use { it.code }
            assertEquals(429, status())
            assertEquals(120_000L, policy.cooldownRemainingMillis)
            now = 60_000
            assertEquals(429, status())
            assertEquals(60_000L, policy.cooldownRemainingMillis)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `cold history is paced below provider budget rather than rejected`() {
        MockWebServer().use { server ->
            repeat(91) { server.enqueue(MockResponse().setBody("tile")) }
            var now = 0L
            val starts = mutableListOf<Long>()
            val client = OkHttpClient.Builder().addInterceptor(RadarTileRequests(nowMillis = { now }, pause = { now += it }))
                .addInterceptor { chain -> starts += now; chain.proceed(chain.request()) }.build()
            fun status(index: Int) = client.newCall(Request.Builder().url(server.url("/$index.png")).build())
                .execute().use { it.code }
            repeat(91) { assertEquals(200, status(it)) }
            assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 750 })
            assertEquals(80, starts.count { it < 60_000 })
            assertEquals(91, server.requestCount)
        }
    }

    @Test fun `shared IP throttling backs off honors Retry After and still serves warm tiles`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("warm"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            server.enqueue(MockResponse().setBody("recovered"))
            var now = 0L
            val client = OkHttpClient.Builder().addInterceptor(RadarTileRequests(nowMillis = { now }, pause = { now += it })).build()
            fun status(path: String) = client.newCall(Request.Builder().url(server.url(path)).build())
                .execute().use { it.code }
            assertEquals(200, status("/warm.png"))
            assertEquals(429, status("/cold.png"))
            now = 60_000
            assertEquals(429, status("/cold.png"))
            assertEquals(200, status("/warm.png"))
            assertEquals(2, server.requestCount)
            now = 120_750
            assertEquals(200, status("/cold.png"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `warm tiles are reused in session and memory eviction redownloads only evicted tiles`() {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setBody("tile")) }
            val client = OkHttpClient.Builder().addInterceptor(RadarTileRequests(maxBytes = 8)).build()
            fun tile(path: String) = client.newCall(Request.Builder().url(server.url(path)).build())
                .execute().use { assertEquals("tile", it.body!!.string()) }
            tile("/one.png"); tile("/two.png")
            assertEquals(2, server.requestCount)
            tile("/one.png"); tile("/two.png")
            assertEquals("Warm loop makes no network requests", 2, server.requestCount)
            tile("/three.png"); tile("/one.png")
            assertEquals("Memory bounded by evicting least recently used", 4, server.requestCount)
        }
    }
}
