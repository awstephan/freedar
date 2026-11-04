package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class InitialBasemapRenderingTest {
    @Test fun basemapWaitsForItsTilesButNotConcurrentRadarLoading() = independentReadiness(radarFirst = false)

    @Test fun readyRadarPreparationDoesNotWaitForUnrelatedBasemapTiles() = independentReadiness(radarFirst = true)

    private fun independentReadiness(radarFirst: Boolean) = withFreshMapRequestBudget {
        val png = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GREEN)
                compress(Bitmap.CompressFormat.PNG, 100, this@output)
                recycle()
            }
        }.toByteArray()
        val tileRequested = CountDownLatch(1)
        val releaseTiles = CountDownLatch(1)
        val radarRequested = CountDownLatch(1)
        val releaseRadar = CountDownLatch(1)
        val radarReady = CountDownLatch(1)
        val radarPrepared = CountDownLatch(1)
        val styleLoaded = CountDownLatch(1)
        val imageryReady = CountDownLatch(1)
        var failure: Throwable? = null
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/style.json") return MockResponse().setBody("""{
                        "version":8,"sources":{"base":{"type":"raster","tileSize":256,
                        "tiles":["http://127.0.0.1:${server.port}/base/{z}/{x}/{y}.png"],"maxzoom":0}},
                        "layers":[{"id":"base","type":"raster","source":"base"}]}""")
                    if (request.path!!.startsWith("/radar/")) {
                        radarRequested.countDown()
                        releaseRadar.await(30, TimeUnit.SECONDS)
                    } else {
                        tileRequested.countDown()
                        releaseTiles.await(30, TimeUnit.SECONDS)
                    }
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                }
            }
            val styleUrl = server.url("/style.json").toString()
            ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
                lateinit var map: RadarMap
                scenario.onActivity { activity ->
                    map = RadarMap(activity)
                    map.view.onCreate(null)
                    activity.setContentView(map.view)
                    map.view.onStart(); map.view.onResume()
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            map.loadStyle(styleUrl)
                            styleLoaded.countDown()
                            launch {
                                try {
                                    map.showWhenReady(com.freedar.radar.RadarFrame(java.time.Instant.ofEpochSecond(1700000000),
                                        "http://127.0.0.1:${server.port}/radar/{z}/{x}/{y}.png"),
                                        { if (!it) radarPrepared.countDown() }) {}
                                } catch (error: Throwable) { failure = error }
                                finally { radarReady.countDown() }
                            }
                            map.awaitInitialBasemap()
                        } catch (error: Throwable) { failure = error }
                        finally { styleLoaded.countDown(); imageryReady.countDown() }
                    }
                }
                try {
                    assertTrue("Style load completed", styleLoaded.await(35, TimeUnit.SECONDS))
                    failure?.let { throw AssertionError("Style failed", it) }
                    assertTrue(tileRequested.await(30, TimeUnit.SECONDS))
                    assertTrue("Radar starts while basemap is pending", radarRequested.await(30, TimeUnit.SECONDS))
                    assertFalse("Style readiness must not stand in for imagery", imageryReady.await(300, TimeUnit.MILLISECONDS))
                    if (radarFirst) {
                        releaseRadar.countDown()
                        assertTrue("Radar preparation ends independently of basemap rendering", radarPrepared.await(10, TimeUnit.SECONDS))
                        assertEquals(1L, imageryReady.count)
                    }
                    releaseTiles.countDown()
                    assertTrue(imageryReady.await(30, TimeUnit.SECONDS))
                    if (!radarFirst) assertEquals("Radar remains pending after basemap readiness", 1L, radarReady.count)
                    failure?.let { throw AssertionError("Initial imagery failed", it) }
                    releaseRadar.countDown()
                    assertTrue(radarReady.await(30, TimeUnit.SECONDS))
                    failure?.let { throw AssertionError("Radar failed", it) }
                } finally {
                    releaseTiles.countDown()
                    releaseRadar.countDown()
                    scenario.onActivity { map.dispose(); map.view.onPause(); map.view.onStop(); map.view.onDestroy() }
                }
            }
        }
    }
}
