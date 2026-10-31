package com.freedar.radar

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LatestRadarTest {
    @Test fun `unrenderable newest imagery is a refresh failure not a stale feed`() = runTest {
        val old = RadarFrame(Instant.ofEpochSecond(1700000000), "https://example.net/old")
        val fresh = old.copy(time = old.time.plusSeconds(1800), tileTemplate = "https://example.net/new")
        var available = old
        val app = LatestRadar(RadarProvider { available }, Clock.fixed(fresh.time, ZoneOffset.UTC),
            RadarDisplay { if (it == fresh) error("tile unavailable") })
        app.refresh()
        assertTrue(app.state.value.stale)
        available = fresh
        app.refresh()
        assertEquals(old, app.state.value.frame)
        assertNotNull(app.state.value.error)
        assertFalse(app.state.value.stale)
        assertEquals(1800L, app.state.value.ageSeconds)
    }

    @Test fun `leaving foreground cancels a pending provider request promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val app = LatestRadar(RainViewer(OkHttpClient(), server.url("/manifest")), Clock.systemUTC(), RadarDisplay {})
            val refresh = launch { app.refresh() }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(2000) { refresh.cancelAndJoin() }
            assertFalse(app.state.value.loading)
            assertNull(app.state.value.error)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `repeated refresh while loading does not start competing requests`() = runTest {
        var requests = 0
        val response = CompletableDeferred<RadarFrame>()
        val app = LatestRadar(RadarProvider { requests++; response.await() }, Clock.systemUTC(), RadarDisplay {})
        val first = launch { app.refresh() }
        runCurrent()
        assertTrue(app.state.value.loading)
        val second = launch { app.refresh() }
        runCurrent()
        assertEquals(1, requests)
        first.cancel(); second.cancel()
    }

    @Test fun `unusable manifests show unavailable radar rather than clear weather`() = runTest {
        val bodies = listOf(
            "not json",
            """{"host":"https://example.net","radar":{"past":[]}}""",
            """{"host":"http://example.net","radar":{"past":[{"time":1,"path":"/radar"}]}}""",
            """{"host":"https://example.net","radar":{"past":[{"time":1,"path":"//other.net/radar"}]}}""",
        )
        MockWebServer().use { server ->
            for (body in bodies) {
                server.enqueue(MockResponse().setBody(body))
                val app = LatestRadar(RainViewer(OkHttpClient(), server.url("/manifest")), Clock.systemUTC(), RadarDisplay {})
                app.refresh()
                assertNull(body, app.state.value.frame)
                assertNotNull(body, app.state.value.error)
                assertFalse(app.state.value.loading)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `frame time changes only when replacement is rendered`() = runTest {
        val old = RadarFrame(Instant.ofEpochSecond(1700000000), "https://example.net/old")
        val next = old.copy(time = old.time.plusSeconds(600), tileTemplate = "https://example.net/new")
        var available = old
        val ready = CompletableDeferred<Unit>()
        val app = LatestRadar(RadarProvider { available }, Clock.fixed(next.time, ZoneOffset.UTC),
            RadarDisplay { if (it == next) ready.await() })
        app.refresh()
        available = next
        val refresh = launch { app.refresh() }
        runCurrent()
        assertTrue(app.state.value.loading)
        assertEquals(old, app.state.value.frame)
        ready.complete(Unit)
        refresh.join()
        assertEquals(next, app.state.value.frame)
    }

    @Test fun `age crosses twenty minutes without downloading a new manifest`() = runTest {
        val frame = RadarFrame(Instant.ofEpochSecond(1700000000), "https://example.net/tiles")
        var now = frame.time.plusSeconds(1200)
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val app = LatestRadar(RadarProvider { frame }, clock, RadarDisplay {})
        app.refresh()
        assertFalse(app.state.value.stale)
        now = now.plusSeconds(1)
        app.updateAge()
        assertTrue(app.state.value.stale)
        assertEquals(1201, app.state.value.ageSeconds)
    }

    @Test fun `failed refresh retains original frame and retry recovers`() = runTest {
        val frame = RadarFrame(Instant.ofEpochSecond(1700000000), "https://example.net/tiles")
        var fail = false
        val app = LatestRadar(RadarProvider {
            if (fail) error("offline") else frame
        }, Clock.fixed(frame.time, ZoneOffset.UTC), RadarDisplay {})
        app.refresh()
        fail = true
        app.refresh()
        assertEquals(frame, app.state.value.frame)
        assertNotNull(app.state.value.error)
        assertFalse(app.state.value.loading)
        fail = false
        app.refresh()
        assertEquals(frame, app.state.value.frame)
        assertNull(app.state.value.error)
    }

    @Test fun `opening chooses newest historical frame using returned host and path`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"generated":1900000000,"host":"https://radar.example.net",
                 "radar":{"past":[{"time":1700000600,"path":"/custom/newest"},
                                    {"time":1700000000,"path":"/custom/older"}],
                          "nowcast":[{"time":1900000000,"path":"/future"}]}}
            """.trimIndent()))
            val app = LatestRadar(
                RainViewer(OkHttpClient(), server.url("/manifest")),
                Clock.fixed(Instant.ofEpochSecond(1700000700), ZoneOffset.UTC),
                RadarDisplay {},
            )
            app.refresh()
            assertEquals(1700000600L, app.state.value.frame?.time?.epochSecond)
            assertEquals("https://radar.example.net/custom/newest/256/{z}/{x}/{y}/2/1_1.png",
                app.state.value.frame?.tileTemplate)
            assertFalse(app.state.value.loading)
            assertNull(app.state.value.error)
        }
    }
}
