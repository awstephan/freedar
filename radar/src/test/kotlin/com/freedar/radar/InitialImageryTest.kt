package com.freedar.radar

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InitialImageryTest {
    private val frame = RadarFrame(Instant.ofEpochSecond(1700000000), "https://example.net/radar")
    private val clock = Clock.fixed(frame.time, ZoneOffset.UTC)

    @Test fun `background immediately withdraws first image work before cancellation finishes`() = runTest {
        val image = CompletableDeferred<Unit>()
        val radar = LatestRadar(RadarProvider { frame }, clock, RadarDisplay { image.await() })
        val opening = launch { radar.refresh() }
        runCurrent()
        assertTrue(radar.state.value.initialImageryLoading)
        radar.background()
        assertFalse(radar.state.value.initialImageryLoading)
        opening.cancel()
        opening.join()
    }

    @Test fun `metadata failure image failure and renderer timeout end startup feedback and retain Info errors`() = runTest {
        val metadataFailure = LatestRadar(RadarProvider { error("offline") }, clock, RadarDisplay {})
        metadataFailure.refresh()
        assertFalse(metadataFailure.state.value.initialImageryLoading)
        assertNotNull(metadataFailure.state.value.refreshError)
        for (message in listOf("Radar tiles unavailable", "Radar tiles timed out")) {
            val image = CompletableDeferred<Unit>()
            val radar = LatestRadar(RadarProvider { frame }, clock, RadarDisplay { image.await() })
            val opening = launch { radar.refresh() }
            runCurrent()
            assertTrue(radar.state.value.initialImageryLoading)
            image.completeExceptionally(java.io.IOException(message))
            opening.join()
            assertFalse(radar.state.value.initialImageryLoading)
            assertNotNull(radar.state.value.imageError)
        }
    }

    @Test fun `cooldown removes startup contribution until a real retry and cancellation ends it`() = runTest {
        var cooling = true
        val image = CompletableDeferred<Unit>()
        val radar = LatestRadar(RadarProvider { frame }, clock, RadarDisplay {
            if (cooling) throw RadarCoolingDown(1000)
            image.await()
        }, { testScheduler.currentTime })
        val opening = launch { radar.refresh() }
        runCurrent()
        assertEquals(1L, radar.state.value.cooldownSeconds)
        assertTrue(radar.state.value.loading)
        assertFalse(radar.state.value.initialImageryLoading)
        cooling = false
        testScheduler.advanceTimeBy(1000)
        runCurrent()
        assertTrue(radar.state.value.initialImageryLoading)
        opening.cancel()
        opening.join()
        assertFalse(radar.state.value.initialImageryLoading)
        assertNull(radar.state.value.frame)
    }

    @Test fun `startup represents metadata and first image but refresh with usable imagery stays quiet`() = runTest {
        val metadata = CompletableDeferred<RadarFrame>()
        var image = CompletableDeferred<Unit>()
        val radar = LatestRadar(RadarProvider { metadata.await() }, clock, RadarDisplay { image.await() })
        val opening = launch { radar.refresh() }
        runCurrent()
        assertTrue(radar.state.value.initialImageryLoading)
        metadata.complete(frame)
        runCurrent()
        assertTrue(radar.state.value.initialImageryLoading)
        image.complete(Unit)
        opening.join()
        assertFalse(radar.state.value.initialImageryLoading)
        assertEquals(frame, radar.state.value.frame)

        image = CompletableDeferred()
        val refresh = launch { radar.refresh() }
        runCurrent()
        assertTrue(radar.state.value.loading)
        assertFalse(radar.state.value.initialImageryLoading)
        image.complete(Unit)
        refresh.join()
    }
}
