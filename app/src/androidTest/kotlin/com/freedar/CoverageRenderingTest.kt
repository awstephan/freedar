package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freedar.radar.RadarFrame
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

@RunWith(AndroidJUnit4::class)
class CoverageRenderingTest {
    @Test fun outsideFootprintShadesMapAndFailureCanRetryWithoutLosingRadar() {
        fun png(color: Int) = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, this@output); recycle()
            }
        }.toByteArray()
        val black = png(Color.BLACK)
        val clear = png(Color.TRANSPARENT)
        MockWebServer().use { server ->
            val failed = AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    if (path.startsWith("/retry/") && failed.get()) return MockResponse().setResponseCode(503)
                    return MockResponse().setBody(Buffer().write(if (path.startsWith("/black/")) black else clear))
                }
            }
            server.start()
            val host = "http://127.0.0.1:${server.port}/"
            withMap { map ->
                val radar = RadarFrame(Instant.ofEpochSecond(1700000000), "${host}radar/256/{z}/{x}/{y}/2/1_1.png")
                suspend fun snapshot(): Int {
                    map.show(radar)
                    val result = CompletableDeferred<Int>()
                    map.nativeMap.snapshot { result.complete(it.getPixel(it.width / 2, it.height / 2)) }
                    return result.await()
                }
                val unshaded = snapshot()
                map.showCoverage("${host}black/256/{z}/{x}/{y}/0/0_0.png")
                withTimeout(15000) { map.coverageStatus.first { it == CoverageStatus.READY } }
                val shaded = snapshot()
                assertTrue("Black footprint pixels visibly shade the map", Color.red(unshaded) - Color.red(shaded) > 30)
                map.showCoverage("${host}retry/256/{z}/{x}/{y}/0/0_0.png")
                withTimeout(15000) { map.coverageStatus.first { it == CoverageStatus.UNAVAILABLE } }
                snapshot() // Coverage failure must not make radar unavailable.
                failed.set(false)
                map.showCoverage("${host}retry/256/{z}/{x}/{y}/0/0_0.png")
                withTimeout(15000) { map.coverageStatus.first { it == CoverageStatus.READY } }
                val covered = snapshot()
                assertTrue("Transparent footprint leaves the map unshaded", kotlin.math.abs(Color.red(unshaded) - Color.red(covered)) < 5)
            }
        }
    }

    @Test fun stalledCoverageCannotBlockHealthyRadarPresentation() {
        val png = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                compress(Bitmap.CompressFormat.PNG, 100, this@output)
                recycle()
            }
        }.toByteArray()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.path!!.startsWith("/coverage/")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else MockResponse().setBody(Buffer().write(png))
            }
            server.start()
            val host = "http://127.0.0.1:${server.port}/"
            withMap { map ->
                map.showCoverage("${host}coverage/256/{z}/{x}/{y}/0/0_0.png")
                withTimeout(8000) {
                    map.show(RadarFrame(Instant.ofEpochSecond(1700000000),
                        "${host}radar/256/{z}/{x}/{y}/2/1_1.png"))
                }
            }
        }
    }

    private fun withMap(test: suspend (RadarMap) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
            lateinit var map: RadarMap
            var job: kotlinx.coroutines.Job? = null
            try {
                scenario.onActivity { activity ->
                    map = RadarMap(activity)
                    activity.setContentView(map.view)
                    map.view.onCreate(null); map.view.onStart(); map.view.onResume()
                    job = CoroutineScope(Dispatchers.Main).launch {
                        try {
                            map.loadStyle(null)
                            map.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 10.0))
                            test(map)
                        } catch (error: Throwable) { failure = error }
                        finally { done.countDown() }
                    }
                }
                assertTrue("Native coverage scenario completed", done.await(60, TimeUnit.SECONDS))
                failure?.let { throw AssertionError("Native coverage", it) }
            } finally {
                scenario.onActivity {
                    job?.cancel(); map.stopCoverage()
                    map.view.onPause(); map.view.onStop(); map.view.onDestroy()
                }
            }
        }
    }
}
