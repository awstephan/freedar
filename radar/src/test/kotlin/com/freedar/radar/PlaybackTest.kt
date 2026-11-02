package com.freedar.radar

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    @Test fun `throttled playback retains image and retries same frame without marking gaps`() = runTest {
        val attempts = mutableListOf<RadarFrame>()
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            attempts += it
            if (it == frames.first() && testScheduler.currentTime < 2000) throw RadarCoolingDown(2000)
        }, monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        val playing = launch { app.play() }; runCurrent()
        assertEquals(frames.last(), app.state.value.frame)
        assertEquals(2L, app.state.value.cooldownSeconds)
        assertTrue(app.state.value.unavailable.isEmpty())
        assertNull(app.state.value.imageError)
        advanceTimeBy(1000); runCurrent()
        assertEquals(1L, app.state.value.cooldownSeconds)
        assertEquals(listOf(frames.last(), frames.first()), attempts)
        advanceTimeBy(1000); runCurrent()
        assertEquals(frames.first(), app.state.value.frame)
        assertEquals(0L, app.state.value.cooldownSeconds)
        assertTrue(app.state.value.unavailable.isEmpty())
        app.pause(); playing.join()
    }

    @Test fun `cached selection supersedes cooldown and cancels its automatic retry`() = runTest {
        val attempts = mutableListOf<RadarFrame>()
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            attempts += it
            if (it == frames.first()) throw RadarCoolingDown(60_000)
        }, monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        val cold = launch { app.select(frames.first()) }; runCurrent()
        assertEquals(60L, app.state.value.cooldownSeconds)
        app.select(frames[1]); cold.join()
        advanceTimeBy(61_000); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        assertEquals(0L, app.state.value.cooldownSeconds)
        assertEquals(listOf(frames.last(), frames.first(), frames[1]), attempts)
        assertTrue(app.state.value.unavailable.isEmpty())
    }

    @Test fun `foreground retries a selection interrupted by cooldown without marking it unavailable`() = runTest {
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            if (it == frames.first() && testScheduler.currentTime < 2000) throw RadarCoolingDown(2000)
        }, monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        val cold = launch { app.select(frames.first()) }; runCurrent()
        assertEquals(2L, app.state.value.cooldownSeconds)
        app.background(); runCurrent(); cold.join()
        advanceTimeBy(3000)
        app.foreground()
        assertEquals(frames.first(), app.state.value.frame)
        assertEquals(TimelineMode.SELECTED, app.state.value.mode)
        assertEquals(0L, app.state.value.cooldownSeconds)
        assertTrue(app.state.value.unavailable.isEmpty())
    }

    @Test fun `foreground resumes cooldown recovery for a paused panned frame`() = runTest {
        var throttled = false
        var attempts = 0
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {
            attempts++
            if (throttled) throw RadarCoolingDown(2000)
        }, monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        app.select(frames[1])
        throttled = true
        val retry = launch { app.retryViewport() }; runCurrent()
        assertEquals(2L, app.state.value.cooldownSeconds)
        app.background(); runCurrent(); retry.join()
        val before = attempts
        throttled = false
        app.foreground()
        assertEquals(before + 1, attempts)
        assertEquals(frames[1], app.state.value.frame)
        assertEquals(TimelineMode.SELECTED, app.state.value.mode)
    }

    @Test fun `speed choices change presentation cadence but newest always holds one second`() = runTest {
        for ((speed, interval) in listOf(PlaybackSpeed.SLOW to 1000L, PlaybackSpeed.NORMAL to 500L,
            PlaybackSpeed.FAST to 250L)) {
            val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {},
                monotonicMillis = { testScheduler.currentTime })
            app.speed = speed
            app.refresh()
            val playing = launch { app.play() }; runCurrent()
            advanceTimeBy(interval - 1); runCurrent()
            assertEquals(frames[0], app.state.value.frame)
            advanceTimeBy(1); runCurrent()
            assertEquals(frames[1], app.state.value.frame)
            advanceTimeBy(interval); runCurrent()
            assertEquals(frames[2], app.state.value.frame)
            advanceTimeBy(999); runCurrent()
            assertEquals(frames[2], app.state.value.frame)
            advanceTimeBy(1); runCurrent()
            assertEquals(frames[0], app.state.value.frame)
            app.pause(); playing.join()
        }
    }

    @Test fun `changing speed while playing takes effect at the next interval without restarting history`() = runTest {
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {},
            monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        val playing = launch { app.play() }; runCurrent()
        app.speed = PlaybackSpeed.FAST
        advanceTimeBy(500); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        advanceTimeBy(249); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        advanceTimeBy(1); runCurrent()
        assertEquals(frames[2], app.state.value.frame)
        app.speed = PlaybackSpeed.SLOW
        advanceTimeBy(999); runCurrent()
        assertEquals(frames[2], app.state.value.frame)
        advanceTimeBy(1); runCurrent()
        assertEquals(frames[0], app.state.value.frame)
        advanceTimeBy(999); runCurrent()
        assertEquals(frames[0], app.state.value.frame)
        advanceTimeBy(1); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        app.pause(); playing.join()
    }

    @Test fun `superseded refresh cannot clear loading for a newer scrub presentation`() = runTest {
        var available = frames
        val pending = CompletableDeferred<Unit>()
        val app = LatestRadar(object : RadarProvider {
            override suspend fun latest() = available.last()
            override suspend fun history() = available
        }, Clock.systemUTC(), RadarDisplay { if (it != frames.last()) pending.await() })
        app.refresh()
        available = frames + frames.last().copy(time = frames.last().time.plusSeconds(600))
        val refresh = launch { app.refresh() }; runCurrent()
        val scrub = launch { app.select(frames.first()) }; runCurrent()
        refresh.join()
        assertTrue("A newer selection is still loading", app.state.value.loading)
        pending.complete(Unit); scrub.join()
        assertFalse(app.state.value.loading)
        assertEquals(frames.first(), app.state.value.frame)
    }

    @Test fun `activity recreation restores selected or playing frame without jumping to latest`() = runTest {
        for (mode in listOf(TimelineMode.SELECTED, TimelineMode.PLAYING)) {
            val shown = mutableListOf<RadarFrame>()
            val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay { shown += it },
                monotonicMillis = { testScheduler.currentTime },
                restoredSelection = frames[1], restoredMode = mode)
            app.refresh()
            assertEquals(listOf(frames[1]), shown)
            assertEquals(mode, app.state.value.mode)
            if (mode == TimelineMode.PLAYING) {
                val playing = launch { app.play(resume = true) }; runCurrent()
                assertEquals(frames.last(), app.state.value.frame)
                app.pause(); playing.join()
            }
        }
    }

    @Test fun `new manifest preempts cold history to prepare newest without changing displayed time`() = runTest {
        var available = frames
        val coldHistory = CompletableDeferred<Unit>()
        val newestReady = CompletableDeferred<Unit>()
        val new = frames.last().copy(time = frames.last().time.plusSeconds(600), tileTemplate = "https://example.net/new")
        val work = mutableListOf<String>()
        val app = LatestRadar(object : RadarProvider {
            override suspend fun latest() = available.last()
            override suspend fun history() = available
        }, Clock.systemUTC(), object : RadarDisplay {
            override suspend fun show(frame: RadarFrame) {
                work += "show:${frame.tileTemplate}"
                if (frame == frames.first()) coldHistory.await()
            }
            override suspend fun prepare(frame: RadarFrame) {
                work += "prepare:${frame.tileTemplate}"
                newestReady.await()
            }
        }, monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        val playing = launch { app.play() }; runCurrent()
        available = frames + new
        val refresh = launch { app.refresh() }; runCurrent()
        assertEquals("prepare:https://example.net/new", work.last())
        assertEquals(frames.last(), app.state.value.frame)
        coldHistory.complete(Unit); runCurrent()
        assertEquals("prepare:https://example.net/new", work.last())
        newestReady.complete(Unit); runCurrent()
        assertEquals(frames.first(), app.state.value.frame)
        assertEquals(TimelineMode.PLAYING, app.state.value.mode)
        app.pause(); playing.join(); refresh.join()
    }

    @Test fun `warm rendering cost does not accumulate drift in two fps cadence or newest hold`() = runTest {
        val presentedAt = mutableListOf<Long>()
        val app = LatestRadar(provider(), Clock.systemUTC(), object : RadarDisplay {
            override suspend fun show(frame: RadarFrame) { presentedAt += testScheduler.currentTime }
            override suspend fun showWhenReady(frame: RadarFrame, awaitPresentationTime: suspend () -> Unit) {
                delay(if (frame == frames.last()) 400 else 200)
                awaitPresentationTime()
                show(frame)
            }
        }, monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        presentedAt.clear()
        val playing = launch { app.play() }
        advanceTimeBy(2300); runCurrent()
        assertEquals(listOf(600L, 1100L, 1600L, 2600L), presentedAt)
        app.pause(); playing.join()
    }

    @Test fun `rolling history advances by displayed frame time rather than stale list index`() = runTest {
        var available = frames
        val app = LatestRadar(object : RadarProvider {
            override suspend fun latest() = available.last()
            override suspend fun history() = available
        }, Clock.systemUTC(), RadarDisplay {}, monotonicMillis = { testScheduler.currentTime })
        app.refresh()
        val playing = launch { app.play() }; runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(frames[1], app.state.value.frame)
        available = frames.drop(1) + frames.last().copy(time = frames.last().time.plusSeconds(600))
        app.refresh()
        advanceTimeBy(500); runCurrent()
        assertEquals(frames[2], app.state.value.frame)
        app.pause(); playing.join()
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
        }, monotonicMillis = { testScheduler.currentTime })
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
        }, Clock.systemUTC(), RadarDisplay {}, monotonicMillis = { testScheduler.currentTime })
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
        val app = LatestRadar(provider(), Clock.systemUTC(), RadarDisplay {},
            monotonicMillis = { testScheduler.currentTime })
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
