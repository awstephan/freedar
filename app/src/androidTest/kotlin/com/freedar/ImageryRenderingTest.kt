package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.freedar.radar.RadarFrame
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.module.http.HttpRequestUtil

class ImageryRenderingTest {
    @Test fun nativeRendererReusesPermittedDiskBytesAndCannotBypassClear() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FreedarApplication
        val directory = java.io.File(app.cacheDir, "imagery-rendering-test")
        directory.deleteRecursively()
        val server = MockWebServer()
        val png = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, this@output); recycle()
            }
        }.toByteArray()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "image/png").setHeader("Vary", "Accept-Encoding")
                .setBody(Buffer().write(png))
        }
        server.start()
        val frame = RadarFrame(Instant.ofEpochSecond(1700000000),
            "http://127.0.0.1:${server.port}/carto/{z}/{x}/{y}.png")
        fun installStore(): DownloadedImagery {
            val store = DownloadedImagery(directory)
            val client = app.httpClient.newBuilder().apply {
                // Production CARTO hosts bypass radar's in-memory reuse; this local
                // fixture must do the same rather than misclassify /carto/*.png as radar.
                interceptors().removeAll { it is ImageryRequests || it is com.freedar.radar.RadarTileRequests }
                addInterceptor(ImageryRequests(store, permitted = { it.encodedPath.startsWith("/carto/") }))
            }.build()
            HttpRequestUtil.setOkHttpClient(client)
            return store
        }
        fun render(expectAvailable: Boolean) {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
                lateinit var map: RadarMap
                scenario.onActivity { activity ->
                    map = RadarMap(activity)
                    activity.setContentView(map.view)
                    map.view.onCreate(null); map.view.onStart(); map.view.onResume()
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            map.loadStyle(null)
                            if (expectAvailable) {
                                map.show(frame)
                                map.nativeMap.snapshot { image ->
                                    try {
                                        val pixel = image.getPixel(image.width / 2, image.height / 2)
                                        assertTrue(Color.blue(pixel) > Color.red(pixel) + 50)
                                    } catch (error: Throwable) { failure = error }
                                    done.countDown()
                                }
                            } else {
                                try { map.show(frame); fail("Native storage bypassed Clear") }
                                catch (_: java.io.IOException) { }
                                done.countDown()
                            }
                        } catch (error: Throwable) { failure = error; done.countDown() }
                    }
                }
                try {
                    assertTrue("Native operation completed", done.await(90, TimeUnit.SECONDS))
                    failure?.let { throw AssertionError(it) }
                } finally { scenario.onActivity { map.view.onPause(); map.view.onStop(); map.view.onDestroy() } }
            }
        }
        try {
            val store = installStore()
            render(true)
            assertTrue(store.size() > 0)
            server.shutdown()
            val reopened = installStore()
            render(true)
            reopened.clear()
            installStore()
            render(false)
            assertEquals(0L, reopened.size())
        } finally {
            HttpRequestUtil.setOkHttpClient(app.httpClient)
            server.close(); directory.deleteRecursively()
        }
    }
}
