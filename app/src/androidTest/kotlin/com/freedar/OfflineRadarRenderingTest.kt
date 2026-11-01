package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil

class OfflineRadarRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun offlineLaunchRendersOriginalFrameAndMissingRegionIsUnavailableThenClearPreventsRestoration() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FreedarApplication
        val phase = InstrumentationRegistry.getArguments().getString("radarProcessCheck")
        val processCheck = app.getSharedPreferences("radar-process-check", android.content.Context.MODE_PRIVATE)
        if (phase != "verify") app.imagery.clear()
        val png = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE); compress(Bitmap.CompressFormat.PNG, 100, this@output); recycle()
            }
        }.toByteArray()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
            }
            server.start()
            val frame = RadarFrame(Instant.ofEpochSecond(Instant.now().epochSecond - 2100),
                "${server.url("/opaque/")}256/{z}/{x}/{y}/2/1_1.png")
            fun install(live: RadarProvider) {
                val archive = RadarArchive(app.imagery)
                LifecycleTestActivity.provider = RetainedRadarProvider(live, archive)
                val client = app.httpClient.newBuilder().apply {
                    interceptors().removeAll { it is ImageryRequests || it is com.freedar.radar.RadarTileRequests }
                    addInterceptor(ImageryRequests(app.imagery, radar = archive))
                    addInterceptor(com.freedar.radar.RadarTileRequests())
                }.build()
                HttpRequestUtil.setOkHttpClient(client)
            }
            fun awaitFrame() = compose.waitUntil(30000) {
                compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            fun frameLabel() = compose.onNodeWithText("Frame time", substring = true).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
            try {
                lateinit var label: String
                if (phase != "verify") {
                    install(RadarProvider { frame })
                    ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                        awaitFrame()
                        label = frameLabel()
                        scenario.onActivity { activity -> map(activity.window.decorView)!!.getMapAsync {
                            it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 6.0))
                        } }
                        // Wait for Latest presentation at the new viewport before closing.
                        compose.revealMapControls()
                        compose.onNodeWithText("Latest").performClick()
                        compose.waitForIdle()
                        compose.waitUntil(30000) { compose.onAllNodesWithText("Updating radar…").fetchSemanticsNodes().isEmpty() }
                        assertBlue(scenario)
                    }
                    if (phase == "seed") {
                        processCheck.edit().putString("session", app.processSession).putString("label", label).commit()
                        return@use
                    }
                } else {
                    assertNotNull(processCheck.getString("session", null))
                    assertNotEquals(processCheck.getString("session", null), app.processSession)
                    label = requireNotNull(processCheck.getString("label", null))
                }
                server.shutdown()
                install(RadarProvider { throw IOException("Offline fixture") })
                ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                    awaitFrame()
                    assertEquals(label, frameLabel())
                    compose.onNodeWithText("Saved radar · original frame time, not live data").assertExists()
                    compose.onNodeWithText("Stale radar feed").assertExists()
                    assertBlue(scenario)
                    scenario.onActivity { activity -> map(activity.window.decorView)!!.getMapAsync {
                        it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.0, 139.0), 9.0))
                    } }
                    compose.waitUntil(15000) { compose.onAllNodesWithText("Some radar tiles are unavailable", substring = true)
                        .fetchSemanticsNodes().isNotEmpty() }
                    compose.revealMapControls()
                    compose.onNodeWithText("Settings").performClick()
                    compose.onNodeWithText("Clear downloaded data").performScrollTo().performClick()
                    compose.waitUntil(5000) { compose.onAllNodesWithText("Downloaded imagery cleared").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(0L, app.imagery.size())
                }
                install(RadarProvider { throw IOException("Offline fixture") })
                ActivityScenario.launch(LifecycleTestActivity::class.java).use {
                    compose.waitUntil(30000) { compose.onAllNodesWithText("Radar unavailable").fetchSemanticsNodes().isNotEmpty() }
                    compose.onAllNodesWithText("Frame time", substring = true).assertCountEquals(0)
                }
            } finally {
                HttpRequestUtil.setOkHttpClient(app.httpClient)
                if (phase != "seed") {
                    app.imagery.clear()
                    processCheck.edit().clear().commit()
                    app.getSharedPreferences("remembered-view", android.content.Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
        }
    }

    private fun assertBlue(scenario: ActivityScenario<LifecycleTestActivity>) {
        val snapshot = CountDownLatch(1)
        var blue = false
        scenario.onActivity { activity -> map(activity.window.decorView)!!.getMapAsync { native ->
            native.snapshot { bitmap ->
                val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                blue = Color.blue(pixel) > Color.red(pixel) + 50
                snapshot.countDown()
            }
        } }
        assertTrue(snapshot.await(30, TimeUnit.SECONDS))
        assertTrue("Original blue radar pixels were rendered", blue)
    }
    private fun map(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) map(view.getChildAt(i))?.let { return it }
        return null
    }
}
