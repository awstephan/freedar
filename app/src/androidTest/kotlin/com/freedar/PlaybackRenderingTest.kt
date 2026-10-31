package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freedar.radar.LatestRadar
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import com.freedar.radar.TimelineMode
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

@RunWith(AndroidJUnit4::class)
class PlaybackRenderingTest {
    @Test fun coldAndWarmTransitionsRetainFrameTimeDuringScrubbingAndPan() {
        val requests = CopyOnWriteArrayList<String>()
        val blue = png(Color.BLUE)
        val red = png(Color.RED)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = requireNotNull(request.path)
                    requests += path
                    return MockResponse().setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(if (path.startsWith("/old/")) red else blue))
                        .apply { if (path.startsWith("/slow/")) setBodyDelay(2, TimeUnit.SECONDS) }
                }
            }
            server.start()
            val frames = listOf("old", "slow", "new").mapIndexed { index, name ->
                RadarFrame(Instant.ofEpochSecond(1700000000 + index * 600L),
                    "http://127.0.0.1:${server.port}/$name/256/{z}/{x}/{y}/2/1_1.png")
            }
            val finished = CountDownLatch(1)
            var failure: Throwable? = null
            ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val map = RadarMap(activity)
                    activity.setContentView(map.view)
                    map.view.onCreate(null); map.view.onStart(); map.view.onResume()
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            map.loadStyle(null)
                            map.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 10.0))
                            val app = LatestRadar(object : RadarProvider {
                                override suspend fun latest() = frames.last()
                                override suspend fun history() = frames
                            }, Clock.systemUTC(), map)
                            app.refresh()
                            assertEquals(frames.last(), app.state.value.frame)
                            assertBlue(center(map))
                            val newestCount = requests.size
                            assertTrue(requests.all { it.startsWith("/new/") })
                            app.select(frames.first())
                            assertEquals(frames.first(), app.state.value.frame)
                            val oldPixel = center(map)
                            assertTrue("Old frame is red", Color.red(oldPixel) > Color.blue(oldPixel) + 50)
                            val coldCount = requests.size
                            app.latest(); assertBlue(center(map))
                            app.select(frames.first())
                            center(map)
                            assertEquals("Warm frames reuse tiles without network downloads", coldCount, requests.size)
                            println("Playback requests: newest=$newestCount, cold two-frame total=$coldCount, warm additional=${requests.size - coldCount}")
                            val priorityFrame = frames.last().copy(time = frames.last().time.plusSeconds(600),
                                tileTemplate = frames.last().tileTemplate.replace("/new/", "/priority/"))
                            map.prepare(priorityFrame)
                            assertEquals("Preparing newest never blends it into historical imagery", oldPixel, center(map))
                            assertEquals(frames.first(), app.state.value.frame)
                            println("Priority newest preparation additional requests=${requests.size - coldCount}")
                            val reachedGate = CompletableDeferred<Unit>()
                            val releaseGate = CompletableDeferred<Unit>()
                            val timedTransition = launch {
                                map.showWhenReady(priorityFrame) { reachedGate.complete(Unit); releaseGate.await() }
                            }
                            reachedGate.await()
                            assertEquals("Loaded candidate stays hidden until its presentation deadline", oldPixel, center(map))
                            releaseGate.complete(Unit); timedTransition.join()
                            assertBlue(center(map))
                            app.select(frames.first())
                            val slow = launch { app.select(frames[1]) }
                            withTimeout(10_000) {
                                while (requests.none { it.startsWith("/slow/") }) delay(20)
                            }
                            assertEquals("Timestamp retains displayed red while loading", frames.first(), app.state.value.frame)
                            val retained = center(map)
                            assertTrue("No blank flash during pending replacement", Color.red(retained) > Color.blue(retained) + 50)
                            map.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(41.0, -96.1), 11.0))
                            app.select(frames.last())
                            slow.join()
                            delay(2200) // The cancelled slow response must not overwrite the newer selection.
                            assertEquals(frames.last(), app.state.value.frame)
                            assertEquals(TimelineMode.SELECTED, app.state.value.mode)
                            assertBlue(center(map))
                            assertEquals(11.0, map.nativeMap.cameraPosition.zoom, 0.01)
                            assertEquals(-96.1, map.nativeMap.cameraPosition.target!!.longitude, 0.01)
                            assertTrue("Pan requested another radar tile column", requests.any { it.contains("/7/29/") })
                            assertTrue(requests.toString(), requests.all { it.split('/')[3].toInt() <= 7 })
                            println("Playback final requests (${requests.size}): $requests")
                        } catch (error: Throwable) { failure = error }
                        finally {
                            map.view.onPause(); map.view.onStop(); map.view.onDestroy()
                            finished.countDown()
                        }
                    }
                }
                assertTrue("Playback fixture completed", finished.await(120, TimeUnit.SECONDS))
                failure?.let { throw AssertionError("Playback rendering failed", it) }
            }
        }
    }

    private suspend fun center(map: RadarMap): Int = suspendCancellableCoroutine { continuation ->
        map.nativeMap.snapshot { image ->
            val pixel = image.getPixel(image.width / 2, image.height / 2)
            image.recycle()
            if (continuation.isActive) continuation.resume(pixel)
        }
    }

    private fun assertBlue(pixel: Int) = assertTrue("Newest frame is blue: $pixel",
        Color.blue(pixel) > Color.red(pixel) + 50)

    private fun png(color: Int) = ByteArrayOutputStream().apply output@{
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            compress(Bitmap.CompressFormat.PNG, 100, this@output)
            recycle()
        }
    }.toByteArray()
}
