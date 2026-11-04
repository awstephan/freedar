package com.freedar.radar

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FrameImageryTest {
    private enum class Outcome { FAILURE, TIMEOUT, CANCEL, BACKGROUND }
    private val frames = listOf(0L, 600L, 1200L).map {
        RadarFrame(Instant.ofEpochSecond(1700000000 + it), "https://example.net/$it")
    }
    private val clock = Clock.fixed(frames.last().time, ZoneOffset.UTC)
    private fun provider() = object : RadarProvider {
        override suspend fun latest() = frames.last()
        override suspend fun history() = frames
    }

    @Test fun `new selection restarts delay and stale readiness cannot end its contribution`() = runTest {
        val oldReady = CompletableDeferred<Unit>()
        val newReady = CompletableDeferred<Unit>()
        val radar = LatestRadar(provider(), clock, object : RadarDisplay {
            override suspend fun show(frame: RadarFrame) {}
            override suspend fun showWhenReady(frame: RadarFrame, preparationChanged: (Boolean) -> Unit,
                awaitPresentationTime: suspend () -> Unit) {
                if (frame == frames.first()) withContext(NonCancellable) {
                    oldReady.await()
                    preparationChanged(false)
                }
                if (frame == frames[1]) newReady.await()
                preparationChanged(false)
                awaitPresentationTime()
            }
        })
        val loading = ImageryLoading(backgroundScope)
        backgroundScope.launch { loading.trackRadar(radar.state) }
        radar.refresh()
        val old = launch { radar.select(frames.first()) }
        runCurrent()
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertTrue(loading.visible.value)
        val newer = launch { radar.select(frames[1]) }
        runCurrent()
        assertFalse(loading.visible.value)
        oldReady.complete(Unit)
        runCurrent()
        old.join()
        assertNotNull(radar.state.value.framePreparation)
        testScheduler.advanceTimeBy(199)
        runCurrent()
        assertFalse(loading.visible.value)
        testScheduler.advanceTimeBy(1)
        runCurrent()
        assertTrue(loading.visible.value)
        assertEquals(frames.last(), radar.state.value.frame)
        newReady.complete(Unit)
        newer.join()
        runCurrent()
        assertFalse(loading.visible.value)
        assertEquals(frames[1], radar.state.value.frame)
    }

    @Test fun `ready native playback waits quietly and overlaps one shared basemap indicator`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val radar = LatestRadar(provider(), clock, object : RadarDisplay {
            override suspend fun show(frame: RadarFrame) {}
            override suspend fun showWhenReady(frame: RadarFrame, preparationChanged: (Boolean) -> Unit,
                awaitPresentationTime: suspend () -> Unit) {
                if (frame == frames[1]) ready.await()
                preparationChanged(false)
                awaitPresentationTime()
            }
        }, { testScheduler.currentTime })
        val loading = ImageryLoading(backgroundScope)
        backgroundScope.launch { loading.trackRadar(radar.state) }
        radar.refresh()
        val playing = launch { radar.play() }
        runCurrent()
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertTrue(loading.visible.value)
        val basemap = loading.begin()
        ready.complete(Unit)
        runCurrent()
        assertNull(radar.state.value.framePreparation)
        assertEquals(frames.first(), radar.state.value.frame)
        assertTrue(loading.visible.value)
        loading.end(basemap)
        assertFalse(loading.visible.value)
        testScheduler.advanceTimeBy(300)
        runCurrent()
        assertEquals(frames[1], radar.state.value.frame)
        assertFalse(loading.visible.value)
        testScheduler.advanceTimeBy(500)
        runCurrent()
        assertEquals(frames.last(), radar.state.value.frame)
        testScheduler.advanceTimeBy(999)
        runCurrent()
        assertEquals(frames.last(), radar.state.value.frame)
        assertFalse(loading.visible.value)
        radar.pause()
        playing.join()
    }

    @Test fun `failure timeout and cancellation withdraw frame work while preserving displayed time`() = runTest {
        for (outcome in Outcome.entries) {
            val ready = CompletableDeferred<Unit>()
            val radar = LatestRadar(provider(), clock, RadarDisplay {
                if (it == frames.first()) ready.await()
            })
            radar.refresh()
            val selection = launch { radar.select(frames.first()) }
            runCurrent()
            assertNotNull(radar.state.value.framePreparation)
            when (outcome) {
                Outcome.CANCEL -> selection.cancel()
                Outcome.BACKGROUND -> {
                    radar.background()
                    assertNull(radar.state.value.framePreparation)
                }
                Outcome.FAILURE -> ready.completeExceptionally(java.io.IOException("Radar tiles unavailable"))
                Outcome.TIMEOUT -> ready.completeExceptionally(java.io.IOException("Radar tiles timed out"))
            }
            selection.join()
            assertNull(radar.state.value.framePreparation)
            assertEquals(frames.last(), radar.state.value.frame)
            if (outcome in listOf(Outcome.FAILURE, Outcome.TIMEOUT)) {
                assertNotNull(radar.state.value.imageError)
                assertTrue(frames.first() in radar.state.value.unavailable)
            }
        }
    }

    @Test fun `cooldown dismisses preparation keeps countdown and retries with a fresh delay`() = runTest {
        var ready = CompletableDeferred<Unit>()
        val radar = LatestRadar(provider(), clock, RadarDisplay {
            if (it == frames.first()) ready.await()
        }, { testScheduler.currentTime })
        val loading = ImageryLoading(backgroundScope)
        backgroundScope.launch { loading.trackRadar(radar.state) }
        radar.refresh()
        val selection = launch { radar.select(frames.first()) }
        runCurrent()
        testScheduler.advanceTimeBy(200)
        runCurrent()
        assertTrue(loading.visible.value)
        ready.completeExceptionally(RadarCoolingDown(2000))
        runCurrent()
        assertFalse(loading.visible.value)
        assertNull(radar.state.value.framePreparation)
        assertEquals(2L, radar.state.value.cooldownSeconds)
        assertNull(radar.state.value.imageError)
        assertEquals(frames.last(), radar.state.value.frame)
        ready = CompletableDeferred()
        testScheduler.advanceTimeBy(1000)
        runCurrent()
        assertEquals(1L, radar.state.value.cooldownSeconds)
        testScheduler.advanceTimeBy(1000)
        runCurrent()
        assertNotNull(radar.state.value.framePreparation)
        assertFalse(loading.visible.value)
        testScheduler.advanceTimeBy(199)
        ready.complete(Unit)
        selection.join()
        runCurrent()
        assertFalse(loading.visible.value)
        assertEquals(frames.first(), radar.state.value.frame)
    }

    @Test fun `routine refresh with retained imagery stays quiet`() = runTest {
        var ready = CompletableDeferred<Unit>().apply { complete(Unit) }
        val radar = LatestRadar(provider(), clock, RadarDisplay { ready.await() })
        radar.refresh()
        ready = CompletableDeferred()
        val refresh = launch { radar.refresh() }
        runCurrent()
        assertTrue(radar.state.value.loading)
        assertNull(radar.state.value.framePreparation)
        assertFalse(radar.state.value.initialImageryLoading)
        ready.complete(Unit)
        refresh.join()
    }

    @Test fun `cached playback scheduling is quiet but slow preparation is eligible`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val radar = LatestRadar(provider(), clock, RadarDisplay {
            if (it == frames[1]) ready.await()
        }, { testScheduler.currentTime })
        radar.refresh()
        val playing = launch { radar.play() }
        runCurrent()
        assertEquals(frames.first(), radar.state.value.frame)
        assertTrue(radar.state.value.loading)
        assertNull("Scheduling alone is quiet", radar.state.value.framePreparation)
        testScheduler.advanceTimeBy(500)
        runCurrent()
        assertNotNull(radar.state.value.framePreparation)
        assertEquals(frames.first(), radar.state.value.frame)
        ready.complete(Unit)
        runCurrent()
        assertEquals(frames[1], radar.state.value.frame)
        assertNull(radar.state.value.framePreparation)
        radar.pause()
        playing.join()
    }

    @Test fun `slow selection retains displayed frame and contributes only until ready`() = runTest {
        val ready = CompletableDeferred<Unit>()
        val radar = LatestRadar(provider(), clock, RadarDisplay {
            if (it == frames.first()) ready.await()
        })
        radar.refresh()
        val selection = launch { radar.select(frames.first()) }
        runCurrent()
        assertNotNull(radar.state.value.framePreparation)
        assertEquals(frames.last(), radar.state.value.frame)
        testScheduler.advanceTimeBy(5000)
        assertNotNull(radar.state.value.framePreparation)
        ready.complete(Unit)
        selection.join()
        assertNull(radar.state.value.framePreparation)
        assertEquals(frames.first(), radar.state.value.frame)
    }
}
