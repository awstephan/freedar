package com.freedar

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import kotlinx.coroutines.awaitCancellation
import android.view.View
import android.view.ViewGroup
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

@RunWith(AndroidJUnit4::class)
class FreshnessLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun playbackResumesAfterShortAbsenceAndResetsAtFiveMinutes() {
        val png = ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                compress(Bitmap.CompressFormat.PNG, 100, this@output)
                recycle()
            }
        }.toByteArray()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
            }
            server.start()
            val frames = listOf(0L, 600L).map {
                RadarFrame(Instant.now().minusSeconds(600 - it), "${server.url("/")}frame$it/256/{z}/{x}/{y}/2/1_1.png")
            }
            LifecycleTestActivity.provider = object : RadarProvider {
                override suspend fun latest() = frames.last()
                override suspend fun history() = frames
            }
            LifecycleTestActivity.elapsed = 0
            var saved: android.os.Bundle? = null
            ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                awaitText("Following latest")
                scenario.onActivity { activity ->
                    requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                        it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 6.0))
                    }
                }
                compose.onNodeWithText("Play").performClick()
                awaitText("Pause")
                scenario.moveToState(Lifecycle.State.CREATED)
                LifecycleTestActivity.elapsed = 299999
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitText("Pause")
                scenario.recreate()
                awaitText("Pause")
                scenario.moveToState(Lifecycle.State.CREATED)
                LifecycleTestActivity.elapsed += 300000
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitText("Following latest")
                compose.onNodeWithText("Play").assertExists()
                scenario.onActivity { activity ->
                    requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                        assertEquals(40.0, it.cameraPosition.target!!.latitude, 0.001)
                        assertEquals(-100.0, it.cameraPosition.target!!.longitude, 0.001)
                        assertEquals(6.0, it.cameraPosition.zoom, 0.001)
                    }
                }
                compose.onNodeWithText("Play").performClick()
                awaitText("Pause")
                scenario.onActivity { saved = it.saveBeforeStop() }
            }
            LifecycleTestActivity.elapsed += 300000
            LifecycleTestActivity.savedForTest = saved
            ActivityScenario.launch(LifecycleTestActivity::class.java).use {
                awaitText("Following latest")
                compose.onNodeWithText("Play").assertExists()
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(30000) {
            try { compose.onNodeWithText(text).assertExists(); true } catch (_: AssertionError) { false }
        }
    }

    @Test fun returnRefreshesButStoppedActivityDoesNotPoll() {
        val requests = AtomicInteger()
        LifecycleTestActivity.provider = RadarProvider { requests.incrementAndGet(); error("fixture outage") }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            await { requests.get() == 1 }
            awaitText("Radar unavailable")
            compose.onNodeWithText("Retry").assertExists()
            scenario.moveToState(Lifecycle.State.CREATED)
            val stopped = requests.get()
            Thread.sleep(1000)
            assertEquals(stopped, requests.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { requests.get() == stopped + 1 }
            scenario.recreate()
            await { requests.get() == stopped + 2 }
        }
    }

    @Test fun stoppingCancelsDelayedRefreshAndReturnStartsANewRequest() {
        val requests = AtomicInteger()
        val cancelled = AtomicInteger()
        LifecycleTestActivity.provider = RadarProvider {
            requests.incrementAndGet()
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            await { requests.get() == 1 }
            scenario.moveToState(Lifecycle.State.CREATED)
            await { cancelled.get() == 1 }
            LifecycleTestActivity.elapsed += 300000
            assertEquals(1, requests.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { requests.get() == 2 }
        }
        await { cancelled.get() == 2 }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(50)
        }
        assertTrue("Lifecycle action completed", condition())
    }
}
