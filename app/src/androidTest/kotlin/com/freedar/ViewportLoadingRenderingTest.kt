package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import com.freedar.radar.ImageryLoading
import com.freedar.radar.RadarFrame
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

class ViewportLoadingRenderingTest {
    private enum class Scenario { NAVIGATION, FAILURE, COOLDOWN, REPLACEMENT, TIMEOUT, EVICTION }
    @Test fun currentViewOwnsConcurrentLoadsAndAbandonedCompletionsCannotDismissIt() = viewportScenario(Scenario.NAVIGATION)
    @Test fun failedRadarReleasesOnlyItsContribution() = viewportScenario(Scenario.FAILURE)
    @Test fun coolingRadarReleasesOnlyItsContribution() = viewportScenario(Scenario.COOLDOWN)
    @Test fun frameSelectionDuringPanRechecksTheReplacementViewport() = viewportScenario(Scenario.REPLACEMENT)
    @Test fun timedOutViewportStopsContributingAndReportsFailure() = viewportScenario(Scenario.TIMEOUT)
    @Test fun evictedNativeTilesMustLoadAgainBeforeTheViewIsReady() = viewportScenario(Scenario.EVICTION)

    private fun viewportScenario(case: Scenario) = withViewportNetwork(case) {
        val terminal = when (case) { Scenario.FAILURE -> 500; Scenario.COOLDOWN -> 429; else -> null }
        val timeout = case == Scenario.TIMEOUT
        val replaceFrame = case == Scenario.REPLACEMENT
        val png = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.GREEN)
                compress(Bitmap.CompressFormat.PNG, 100, this@output)
                recycle()
            }
        }.toByteArray()
        val baseRequested = CountDownLatch(1)
        val radarRequested = CountDownLatch(1)
        val newerRequested = CountDownLatch(1)
        val base = CountDownLatch(1)
        val radar = CountDownLatch(1)
        val newer = CountDownLatch(1)
        val evicted = java.util.concurrent.atomic.AtomicBoolean(false)
        val returnRequested = CountDownLatch(1)
        val returning = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/style.json") return MockResponse().setBody("""{
                        "version":8,"sources":{"base":{"type":"raster","tileSize":256,
                        "tiles":["${server.url("/base/")}{z}/{x}/{y}.png"],"maxzoom":7}},
                        "layers":[{"id":"background","type":"background"},
                        {"id":"base","type":"raster","source":"base"}]}""")
                    val parts = request.path!!.split('/')
                    val z = parts[2].toInt()
                    val longitude = (parts[3].toInt() + .5) / (1 shl z) * 360 - 180
                    if (evicted.get() && longitude in -110.0..-90.0) {
                        returnRequested.countDown(); returning.await(30, TimeUnit.SECONDS)
                    }
                    if (longitude in 0.0..30.0) {
                        if (parts[1] == "base") { baseRequested.countDown(); base.await(30, TimeUnit.SECONDS) }
                        else { radarRequested.countDown(); radar.await(if (timeout) 60 else 30, TimeUnit.SECONDS) }
                        if (parts[1] != "base" && terminal != null) return MockResponse().setResponseCode(terminal).setHeader("Retry-After", "2")
                    } else if (longitude in 110.0..150.0) {
                        newerRequested.countDown(); newer.await(30, TimeUnit.SECONDS)
                    }
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                }
            }
            server.start()
            val styleUrl = server.url("/style.json").toString()
            val radarUrl = "${server.url("/radar/")}{z}/{x}/{y}.png"
            ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
                lateinit var map: RadarMap
                lateinit var indicator: ImageryLoading
                val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
                val ready = CompletableDeferred<Unit>()
                val replacementReady = CompletableDeferred<Unit>()
                scenario.onActivity { activity ->
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                    map = RadarMap(activity)
                    map.view.onCreate(null)
                    activity.setContentView(map.view)
                    map.view.onStart(); map.view.onResume()
                    map.view.getMapAsync { native ->
                        native.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 10.0))
                    }
                    indicator = ImageryLoading(scope)
                    scope.launch {
                        try {
                            map.loadStyle(styleUrl)
                            map.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 10.0))
                            launch { indicator.trackViewport(map.viewportLoading) }
                            map.awaitInitialBasemap()
                            map.show(RadarFrame(Instant.now(), radarUrl))
                            ready.complete(Unit)
                        } catch (error: Throwable) { ready.completeExceptionally(error) }
                    }
                }
                var check = 0
                fun visible(value: Boolean) = runBlocking {
                    check++
                    assertNotNull("Visibility check $check expected $value",
                        withTimeoutOrNull(10000) { indicator.visible.first { it == value } })
                }
                fun move(longitude: Double) = scenario.onActivity {
                    map.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, longitude), 10.0))
                }
                try {
                    runBlocking { withTimeout(30000) { ready.await() } }
                    visible(false)
                    move(15.0)
                    assertTrue(baseRequested.await(10, TimeUnit.SECONDS))
                    visible(true)
                    if (terminal != null) {
                        assertTrue(radarRequested.await(10, TimeUnit.SECONDS))
                        // Wait for the public failure/cooldown feedback, not a guessed network delay.
                        runBlocking {
                            withTimeout(10000) {
                                val feedback = async(start = CoroutineStart.UNDISPATCHED) {
                                    if (terminal == 429) map.viewportCooldown.first() else map.status.first { it != null }
                                }
                                radar.countDown()
                                feedback.await()
                            }
                        }
                        assertTrue("Basemap remains active after radar terminates", indicator.visible.value)
                        base.countDown()
                        visible(false)
                        return@use
                    }
                    base.countDown()
                    assertTrue(radarRequested.await(10, TimeUnit.SECONDS))
                    if (case == Scenario.EVICTION) {
                        radar.countDown()
                        visible(false)
                        scenario.onActivity {
                            map.nativeMap.setTileCacheEnabled(false)
                            map.view.onLowMemory()
                        }
                        evicted.set(true)
                        move(-100.0)
                        assertTrue("Evicted view fetches again", returnRequested.await(10, TimeUnit.SECONDS))
                        visible(true)
                        returning.countDown()
                        visible(false)
                        return@use
                    }
                    if (timeout) {
                        scenario.onActivity {
                            scope.launch {
                                repeat(35) { step ->
                                    delay(1000)
                                    map.nativeMap.moveCamera(CameraUpdateFactory.newLatLng(LatLng(40.0, 15.0 + step * .00001)))
                                }
                            }
                        }
                        runBlocking { withTimeout(35000) { indicator.visible.first { !it } } }
                        assertEquals("Radar viewport timed out.", map.status.value)
                        move(15.0001)
                        Thread.sleep(350)
                        assertFalse("Moving does not resurrect a timed-out tile request", indicator.visible.value)
                        return@use
                    }
                    if (replaceFrame) scenario.onActivity {
                        scope.launch {
                            var operation: ImageryLoading.Operation? = null
                            try {
                                map.showWhenReady(RadarFrame(Instant.now().minusSeconds(600), radarUrl.replace("/radar/", "/replacement/")),
                                    { busy ->
                                        if (busy && operation == null) operation = indicator.begin()
                                        if (!busy) { operation?.let(indicator::end); operation = null }
                                    }) {}
                                replacementReady.complete(Unit)
                            } catch (error: Throwable) { replacementReady.completeExceptionally(error) }
                            finally { operation?.let(indicator::end) }
                        }
                    }
                    Thread.sleep(350)
                    assertTrue("Radar keeps the shared indicator active", indicator.visible.value)
                    move(130.0)
                    assertTrue(newerRequested.await(10, TimeUnit.SECONDS))
                    scenario.onActivity {
                        for (zoom in listOf(6.0, 8.0, 10.0)) {
                            map.nativeMap.moveCamera(CameraUpdateFactory.zoomTo(zoom))
                        }
                    }
                    radar.countDown()
                    Thread.sleep(350)
                    assertTrue("Old completions cannot dismiss the new view", indicator.visible.value)
                    // Cached original view is ready even while Japan's requests are blocked.
                    move(-100.0)
                    if (replaceFrame) runBlocking { withTimeout(20000) { replacementReady.await() } }
                    visible(false)
                    move(130.0)
                    visible(true)
                    newer.countDown()
                    visible(false)
                } finally {
                    base.countDown(); radar.countDown(); newer.countDown(); returning.countDown()
                    scenario.onActivity {
                        scope.cancel(); map.dispose()
                        map.view.onPause(); map.view.onStop(); map.view.onDestroy()
                    }
                }
            }
        }
    }

    private fun withViewportNetwork(case: Scenario, test: () -> Unit) {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FreedarApplication
        val client = app.httpClient.newBuilder().apply {
            if (case != Scenario.COOLDOWN) {
                interceptors().removeAll { it is com.freedar.radar.RadarTileRequests }
                interceptors().add(0, com.freedar.radar.RadarTileRequests(maxBytes = if (case == Scenario.EVICTION) 0 else 24L * 1024 * 1024))
            }
            if (case == Scenario.TIMEOUT) { readTimeout(60, TimeUnit.SECONDS); callTimeout(0, TimeUnit.SECONDS) }
        }.build()
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
        try { test() } finally { org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(app.httpClient) }
    }
}
