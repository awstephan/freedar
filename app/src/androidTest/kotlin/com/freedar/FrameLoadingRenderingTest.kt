package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.maps.MapView

class FrameLoadingRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectionAndPlaybackKeepImageAndTimeUntilDelayedFrameIsReady() = withLocalView {
        for (playback in listOf(false, true)) {
            val release = CountDownLatch(1)
            val requested = CountDownLatch(1)
            val red = png(Color.RED)
            val blue = png(Color.BLUE)
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val slow = request.path!!.startsWith("/slow/")
                        if (slow) {
                            requested.countDown()
                            release.await(30, TimeUnit.SECONDS)
                        }
                        return MockResponse().setHeader("Content-Type", "image/png")
                            .setBody(Buffer().write(if (slow) blue else red))
                    }
                }
                server.start()
                val frames = listOf("old", "slow", "latest").mapIndexed { index, path ->
                    RadarFrame(Instant.now().minusSeconds(1200 - index * 600L),
                        "${server.url("/")}$path/{z}/{x}/{y}.png")
                }
                LifecycleTestActivity.provider = object : RadarProvider {
                    override suspend fun latest() = frames.last()
                    override suspend fun history() = frames
                }
                LifecycleTestActivity.elapsed = 0
                try {
                    withFreshMapRequestBudget {
                        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                            compose.awaitRadar()
                            val slider = compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
                            compose.waitUntil(30000) {
                                compose.onAllNodesWithContentDescription("Frame time").fetchSemanticsNodes().isNotEmpty()
                            }
                            val label = compose.onNodeWithContentDescription("Frame time")
                                .fetchSemanticsNode().config[SemanticsProperties.Text]
                            if (playback) compose.onNodeWithContentDescription("Play").performClick()
                            else slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
                            assertTrue(requested.await(10, TimeUnit.SECONDS))
                            val loading = compose.onNodeWithContentDescription("Loading map imagery")
                            compose.waitUntil(10000) { loading.isDisplayed() }
                            assertEquals(label, compose.onNodeWithContentDescription("Frame time")
                                .fetchSemanticsNode().config[SemanticsProperties.Text])
                            assertFalse(loading.fetchSemanticsNode().config.contains(SemanticsProperties.LiveRegion))
                            assertColor(scenario, blue = false)
                            release.countDown()
                            compose.waitUntil(10000) { !loading.isDisplayed() }
                            if (!playback) {
                                compose.waitUntil(10000) {
                                    label != compose.onNodeWithContentDescription("Frame time")
                                        .fetchSemanticsNode().config[SemanticsProperties.Text]
                                }
                                assertColor(scenario, blue = true)
                            } else compose.onNodeWithContentDescription("Pause").performClick()
                        }
                    }
                } finally { release.countDown() }
            }
        }
    }

    private fun withLocalView(test: () -> Unit) {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val view = RememberedView(context)
        val previous = view.read()
        // Bound the tile count so request pacing fits the fixture's readiness deadline,
        // regardless of the camera left by preceding navigation/theme tests.
        view.save(org.maplibre.android.camera.CameraPosition.Builder()
            .target(org.maplibre.android.geometry.LatLng(40.0, -100.0)).zoom(10.0).build())
        try { test() } finally {
            if (previous != null) view.save(previous)
            else context.getSharedPreferences("remembered-view", 0).edit().clear().commit()
        }
    }

    private fun assertColor(scenario: ActivityScenario<LifecycleTestActivity>, blue: Boolean) {
        val complete = CountDownLatch(1)
        var pixel = 0
        scenario.onActivity { activity ->
            requireNotNull(findMap(activity.window.decorView)).getMapAsync { map ->
                map.snapshot { image ->
                    pixel = image.getPixel(image.width / 2, image.height / 2)
                    image.recycle()
                    complete.countDown()
                }
            }
        }
        assertTrue(complete.await(10, TimeUnit.SECONDS))
        if (blue) assertTrue(Color.blue(pixel) > Color.red(pixel) + 50)
        else assertTrue(Color.red(pixel) > Color.blue(pixel) + 50)
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun png(color: Int) = ByteArrayOutputStream().apply output@{
        Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            compress(Bitmap.CompressFormat.PNG, 100, this@output)
            recycle()
        }
    }.toByteArray()
}
