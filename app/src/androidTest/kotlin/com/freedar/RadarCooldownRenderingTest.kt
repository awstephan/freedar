package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import com.freedar.radar.LatestRadar
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class RadarCooldownRenderingTest {
    @Test fun nativeTileThrottleRetainsFrameAndAutomaticallyRecoversSelection() {
        fun png(color: Int) = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color)
                compress(Bitmap.CompressFormat.PNG, 100, this@output)
                recycle()
            }
        }.toByteArray()
        val warmPng = png(Color.GREEN)
        val coldPng = png(Color.BLUE)
        MockWebServer().use { server ->
            val throttled = AtomicBoolean(false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/cold/") && throttled.compareAndSet(false, true))
                        return MockResponse().setResponseCode(429).setHeader("Retry-After", "2")
                    return MockResponse().setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(if (request.path!!.startsWith("/cold/")) coldPng else warmPng))
                }
            }
            server.start()
            val cold = RadarFrame(Instant.ofEpochSecond(1700000000),
                "http://127.0.0.1:${server.port}/cold/{z}/{x}/{y}.png")
            val warm = cold.copy(time = cold.time.plusSeconds(600), tileTemplate = cold.tileTemplate.replace("/cold/", "/warm/"))
            val completed = CountDownLatch(1)
            var failure: Throwable? = null
            var stage = "loading style"
            ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
                lateinit var map: RadarMap
                scenario.onActivity { activity ->
                    map = RadarMap(activity)
                    activity.setContentView(map.view)
                    map.view.onCreate(null); map.view.onStart(); map.view.onResume()
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            map.loadStyle(null)
                            map.centerLocal(LocalPosition(40.0, -100.0))
                            stage = "loading initial frame"
                            val radar = LatestRadar(object : RadarProvider {
                                override suspend fun latest() = warm
                                override suspend fun history() = listOf(cold, warm)
                            }, Clock.systemUTC(), map)
                            radar.refresh()
                            assertEquals(warm, radar.state.value.frame)
                            stage = "recovering selected frame"
                            withTimeout(30_000) {
                                val selecting = launch { radar.select(cold) }
                                radar.state.first { it.cooldownSeconds > 0 }
                                assertNull("Cooldown uses countdown rather than loading", radar.state.value.framePreparation)
                                assertEquals(warm, radar.state.value.frame)
                                assertTrue(radar.state.value.unavailable.isEmpty())
                                assertNull(radar.state.value.imageError)
                                assertImage(map, blue = false)
                                selecting.join()
                            }
                            assertTrue(throttled.get())
                            assertEquals(cold, radar.state.value.frame)
                            assertEquals(0L, radar.state.value.cooldownSeconds)
                            assertNull(radar.state.value.framePreparation)
                            assertTrue(radar.state.value.unavailable.isEmpty())
                            stage = "checking recovered image"
                            assertImage(map, blue = true)
                            stage = "recovering panned viewport"
                            // Paused frames also request tiles when the user pans into a new area.
                            withTimeout(30_000) {
                                val recovery = launch { map.viewportCooldown.collect { radar.retryViewport() } }
                                try {
                                    throttled.set(false)
                                    map.centerLocal(LocalPosition(40.0, -80.0))
                                    radar.state.first { it.cooldownSeconds > 0 }
                                    assertEquals(cold, radar.state.value.frame)
                                    assertTrue(radar.state.value.unavailable.isEmpty())
                                    radar.state.first { it.cooldownSeconds == 0L && !it.loading }
                                    assertImage(map, blue = true)
                                } finally { recovery.cancel() }
                            }
                        } catch (error: Throwable) { failure = error }
                        finally { completed.countDown() }
                    }
                }
                try {
                    val finished = completed.await(90, TimeUnit.SECONDS)
                    assertTrue("Native cooldown completed ($stage)", finished)
                    failure?.let { throw AssertionError("Native throttle recovery failed ($stage)", it) }
                } finally {
                    scenario.onActivity { map.view.onPause(); map.view.onStop(); map.view.onDestroy() }
                }
            }
        }
    }

    private suspend fun assertImage(map: RadarMap, blue: Boolean) {
        val image = withTimeout(10_000) {
            suspendCancellableCoroutine<Bitmap> { continuation ->
                map.nativeMap.snapshot { if (continuation.isActive) continuation.resume(it) }
            }
        }
        val pixel = image.getPixel(image.width / 2, image.height / 2)
        image.recycle()
        if (blue) assertTrue("Recovered image is blue", Color.blue(pixel) > Color.green(pixel) + 50)
        else assertTrue("Previous green image remains visible during cooldown", Color.green(pixel) > Color.blue(pixel) + 50)
    }
}
