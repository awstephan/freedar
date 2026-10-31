package com.freedar

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import java.io.ByteArrayOutputStream
import java.time.Instant
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

@RunWith(AndroidJUnit4::class)
class ControlsPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun accessibleTimelineRetainsSelectionCameraAndPlaybackAcrossBothOrientations() = withRadar { scenario ->
        awaitText("Following latest")
        val slider = compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
        assertTrue(slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains("Frame"))
        scenario.onActivity { activity ->
            requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 6.0))
            }
        }
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        awaitText("Selected frame")
        val selected = slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            compose.rotateAndWait(scenario, orientation)
            awaitText("Selected frame")
            assertEquals(selected, slider.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            scenario.onActivity { activity ->
                requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    assertEquals(40.0, it.cameraPosition.target!!.latitude, 0.001)
                    assertEquals(-100.0, it.cameraPosition.target!!.longitude, 0.001)
                    assertEquals(6.0, it.cameraPosition.zoom, 0.001)
                }
            }
        }
        compose.onNodeWithText("Play").performClick()
        awaitText("Pause")
        compose.rotateAndWait(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        awaitText("Pause")
        compose.rotateAndWait(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        awaitText("Pause")
        compose.waitUntil(8000) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Frame time", substring = true).assertExists()
        compose.onNodeWithText("Blank radar does not mean clear weather.", substring = true).assertExists()
        compose.onNodeWithText("Radar: RainViewer", substring = true).assertExists()
    }

    @Test fun largeTextPlaybackRemainsOperableWhileTimestampAndCreditsStayAnchored() = withLargeText {
        withRadar { scenario ->
            awaitText("Following latest")
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                scenario.onActivity { assertEquals(1.8f, it.resources.configuration.fontScale, 0.01f) }
                compose.onNodeWithContentDescription("Weather map").performClick()
                compose.onNodeWithText("Play").performScrollTo().assertIsDisplayed().performClick()
                awaitText("Pause")
                compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                    .performScrollTo().assertIsDisplayed()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
                awaitText("Selected frame")
                compose.onNodeWithText("Latest").performScrollTo().assertIsDisplayed().performClick()
                awaitText("Following latest")
                compose.waitUntil(8000) { compose.onAllNodesWithText("Play").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithText("Frame time", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Blank radar does not mean clear weather.", substring = true).assertIsDisplayed()
                compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertIsDisplayed()
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun withRadar(test: (ActivityScenario<LifecycleTestActivity>) -> Unit) {
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
            ActivityScenario.launch(LifecycleTestActivity::class.java).use(test)
        }
    }
}
