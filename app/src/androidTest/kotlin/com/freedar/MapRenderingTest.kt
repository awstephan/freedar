package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freedar.radar.RadarFrame
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

@RunWith(AndroidJUnit4::class)
class MapRenderingTest {
    @Test fun nativeRasterRendersAndOverzoomsWithoutHigherTileRequests() {
        val png = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                compress(Bitmap.CompressFormat.PNG, 100, this@output)
                recycle()
            }
        }.toByteArray()
        val requests = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(requireNotNull(request.path))
                    if (request.path!!.startsWith("/missing/")) return MockResponse().setResponseCode(503)
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
                }
            }
            server.start()
            val tileTemplate = "http://127.0.0.1:${server.port}/radar/256/{z}/{x}/{y}/2/1_1.png"
            val rendered = CountDownLatch(1)
            val snapshot = CountDownLatch(1)
            var failure: Throwable? = null
            var pixel = Color.TRANSPARENT
            ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
                lateinit var map: RadarMap
                scenario.onActivity { activity ->
                    map = RadarMap(activity)
                    activity.setContentView(map.view)
                    map.view.onCreate(null)
                    map.view.onStart()
                    map.view.onResume()
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                             map.loadStyle(null)
                             map.showCoverage("http://127.0.0.1:${server.port}/coverage/256/{z}/{x}/{y}/0/0_0.png")
                            map.overview()
                            for (point in listOf(LatLng(49.0, -124.0), LatLng(25.0, -81.0),
                                LatLng(32.5, -117.0), LatLng(45.0, -67.0))) {
                                val pixel = map.nativeMap.projection.toScreenLocation(point)
                                assertTrue("Contiguous US extent visible: $point -> $pixel",
                                    pixel.x in 0f..map.view.width.toFloat() && pixel.y in 0f..map.view.height.toFloat())
                            }
                            map.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 10.0))
                            map.show(RadarFrame(Instant.ofEpochSecond(1700000000),
                                tileTemplate))
                            try {
                                map.show(RadarFrame(Instant.ofEpochSecond(1700000600),
                                    tileTemplate.replace("/radar/", "/missing/")))
                                fail("Unavailable replacement must fail instead of showing clear imagery")
                            } catch (_: IOException) {
                                // Snapshot below must still contain the preceding blue frame.
                            }
                            map.nativeMap.snapshot { image ->
                                pixel = image.getPixel(image.width / 2, image.height / 2)
                                snapshot.countDown()
                            }
                        } catch (error: Throwable) { failure = error }
                        finally { rendered.countDown() }
                    }
                }
                assertTrue("Renderer completed", rendered.await(90, TimeUnit.SECONDS))
                failure?.let { throw AssertionError("Native rendering failed", it) }
                assertTrue("Snapshot completed", snapshot.await(30, TimeUnit.SECONDS))
                println("Native radar requests (${requests.size}): $requests; snapshot center=$pixel")
                assertTrue("Blue fixture really appears in the rendered pixels", Color.blue(pixel) > Color.red(pixel) + 50)
                assertTrue("Radar requests were observed", requests.isNotEmpty())
                assertTrue(requests.toString(), requests.all { it.split('/')[3].toInt() <= 7 })
                assertTrue("Static coverage is requested separately from radar frames", requests.any { it.startsWith("/coverage/") })
                assertTrue("Native zoom-7 tiles used at camera zoom 10", requests.any { it.startsWith("/radar/256/7/") })
                scenario.onActivity {
                    map.dispose(); map.view.onPause(); map.view.onStop(); map.view.onDestroy()
                }
            }
        }
    }
}
