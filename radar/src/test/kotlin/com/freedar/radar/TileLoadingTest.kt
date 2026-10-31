package com.freedar.radar

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TileLoadingTest {
    @Test fun `cold history cannot exceed ninety tile requests in a rolling minute`() {
        MockWebServer().use { server ->
            repeat(91) { server.enqueue(MockResponse().setBody("tile")) }
            var now = 0L
            val client = OkHttpClient.Builder().addInterceptor(RadarTileRequests(nowMillis = { now })).build()
            fun status(index: Int) = client.newCall(Request.Builder().url(server.url("/$index.png")).build())
                .execute().use { it.code }
            repeat(90) { assertEquals(200, status(it)) }
            assertEquals(429, status(90))
            assertEquals(90, server.requestCount)
            now = 60_000
            assertEquals(200, status(90))
            assertEquals(91, server.requestCount)
        }
    }

    @Test fun `shared IP throttling backs off honors Retry After and still serves warm tiles`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("warm"))
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            server.enqueue(MockResponse().setBody("recovered"))
            var now = 0L
            val client = OkHttpClient.Builder().addInterceptor(RadarTileRequests(nowMillis = { now })).build()
            fun status(path: String) = client.newCall(Request.Builder().url(server.url(path)).build())
                .execute().use { it.code }
            assertEquals(200, status("/warm.png"))
            assertEquals(429, status("/cold.png"))
            now = 60_000
            assertEquals(429, status("/cold.png"))
            assertEquals(200, status("/warm.png"))
            assertEquals(2, server.requestCount)
            now = 120_000
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
