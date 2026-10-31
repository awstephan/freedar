package com.freedar.radar

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackTest {
    private val frames = listOf(0L, 600L, 1200L).map {
        RadarFrame(Instant.ofEpochSecond(1700000000 + it), "https://example.net/$it")
    }
    private fun provider() = object : RadarProvider {
        override suspend fun latest() = frames.last()
        override suspend fun history() = frames
    }

    @Test fun `retry refresh recovers unavailable selected frame without following latest`() = runTest {
        var fail = true
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            if (it == frames.first() && fail) error("throttled")
        })
        app.refresh()
        app.select(frames.first())
        assertEquals(frames.last(), app.state.value.frame)
        fail = false
        app.refresh()
        assertEquals(frames.first(), app.state.value.frame)
        assertEquals(TimelineMode.SELECTED, app.state.value.mode)
        assertNull(app.state.value.error)
    }

    @Test fun `pause during loading cancels advancement and keeps displayed selection`() = runTest {
        val pending = CompletableDeferred<Unit>()
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            if (it == frames.first()) pending.await()
        })
        app.refresh()
        val playing = launch { app.play() }; runCurrent()
        app.pause(); runCurrent()
        pending.complete(Unit); runCurrent()
        assertEquals(frames.last(), app.state.value.frame)
        assertEquals(frames.last(), app.state.value.selection)
        assertFalse(app.state.value.loading)
        playing.join()
    }

    @Test fun `play waits for initial newest presentation before spending requests on history`() = runTest {
        val newestReady = CompletableDeferred<Unit>()
        val shown = mutableListOf<RadarFrame>()
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            shown += it
            if (it == frames.last()) newestReady.await()
        })
        launch { app.refresh() }; runCurrent()
        val playing = launch { app.play() }; runCurrent()
        assertEquals(listOf(frames.last()), shown)
        newestReady.complete(Unit); runCurrent()
        assertEquals(listOf(frames.last(), frames.first()), shown)
        app.pause(); playing.join()
    }

    @Test fun `playback skips failed frames retains last image and marks gaps without retrying each loop`() = runTest {
        val attempted = mutableListOf<RadarFrame>()
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            attempted += it
            if (it == frames[1]) error("unavailable")
        })
        app.refresh()
        val playing = launch { app.play() }; runCurrent()
        assertEquals(frames[0], app.state.value.frame)
        advanceTimeBy(500); runCurrent()
        assertEquals(frames[2], app.state.value.frame)
        assertTrue(frames[1] in app.state.value.unavailable)
        advanceTimeBy(1500); runCurrent()
        assertEquals(1, attempted.count { it == frames[1] })
        app.pause(); playing.join()
        app.select(frames[1])
        assertEquals(frames[2], app.state.value.frame)
        assertNotNull(app.state.value.error)
    }

    @Test fun `rapid scrubbing cancels obsolete imagery and publishes only presented time`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val newer = CompletableDeferred<Unit>()
        var cancelled = false
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            if (it == frames[0]) try { slow.await() } finally { cancelled = true }
            if (it == frames[1]) newer.await()
        })
        app.refresh()
        val first = launch { app.select(frames[0]) }
        runCurrent()
        assertEquals(frames[2], app.state.value.frame)
        val second = launch { app.select(frames[1]) }
        runCurrent()
        assertTrue("Obsolete renderer work cancelled", cancelled)
        assertEquals(frames[2], app.state.value.frame)
        newer.complete(Unit); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        slow.complete(Unit); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        first.join(); second.join()
    }

    @Test fun `scrubbing pauses retains selection across refresh and Latest resumes following`() = runTest {
        var available = frames
        val app = LatestRadar(object : RadarProvider {
            override suspend fun latest() = available.last()
            override suspend fun history() = available
        }, Clock.systemUTC(), RadarDisplay {})
        app.refresh()
        launch { app.play() }; runCurrent()
        app.select(frames[1])
        advanceTimeBy(2000); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        assertEquals(TimelineMode.SELECTED, app.state.value.mode)
        val new = RadarFrame(frames.last().time.plusSeconds(600), "https://example.net/new")
        available = frames + new
        app.refresh()
        assertEquals(frames[1], app.state.value.frame)
        assertEquals(new.time, app.state.value.newestFrameTime)
        app.latest()
        assertEquals(new, app.state.value.frame)
        assertEquals(TimelineMode.FOLLOWING, app.state.value.mode)
        available = available + new.copy(time = new.time.plusSeconds(600))
        app.refresh()
        assertEquals(available.last(), app.state.value.frame)
    }

    @Test fun `play begins oldest at two fps holds newest a second loops and pauses`() = runTest {
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {})
        app.refresh()
        val playing = launch { app.play() }
        runCurrent()
        assertEquals(frames[0], app.state.value.frame)
        advanceTimeBy(499); runCurrent()
        assertEquals(frames[0], app.state.value.frame)
        advanceTimeBy(1); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        advanceTimeBy(500); runCurrent()
        assertEquals(frames[2], app.state.value.frame)
        advanceTimeBy(999); runCurrent()
        assertEquals(frames[2], app.state.value.frame)
        advanceTimeBy(1); runCurrent()
        assertEquals(frames[0], app.state.value.frame)
        app.pause()
        advanceTimeBy(2000); runCurrent()
        assertEquals(frames[0], app.state.value.frame)
        assertEquals(TimelineMode.SELECTED, app.state.value.mode)
        playing.join()
    }

    @Test fun `opening loads newest first and exposes ordered returned two hour history`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"generated":1900000000,"host":"https://radar.example.net",
                 "radar":{"past":[{"time":1700007200,"path":"/opaque/new"},
                    {"time":1699999400,"path":"/too-old"},
                    {"time":1700000000,"path":"/opaque/start"},
                    {"time":1700000600,"path":"/opaque/middle"}],
                    "nowcast":[{"time":1900000000,"path":"/future"}]}}
            """.trimIndent()))
            val shown = mutableListOf<RadarFrame>()
            val app = LatestRadar(RainViewer(OkHttpClient(), server.url("/manifest")),
                Clock.fixed(Instant.ofEpochSecond(1700007200), ZoneOffset.UTC), RadarDisplay { shown += it })
            app.refresh()
            assertEquals(listOf(1700000000L, 1700000600L, 1700007200L),
                app.state.value.frames.map { it.time.epochSecond })
            assertEquals(listOf(app.state.value.frames.last()), shown)
            assertEquals("https://radar.example.net/opaque/start/256/{z}/{x}/{y}/2/1_1.png",
                app.state.value.frames.first().tileTemplate)
        }
    }
}
