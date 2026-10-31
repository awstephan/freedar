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

    @Test fun locationFailureAtLargeTextPreservesFrameNoticeCoverageAndCredits() = withLargeText {
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun permission(result: (Boolean) -> Unit) = result(false)
            override fun locate(result: (PositionResult) -> Unit): () -> Unit = error("Permission denied")
        }
        try { withRadar { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.revealMapControls()
                compose.onNodeWithText("Locate").performScrollTo().performClick()
                compose.onNodeWithText("Frame time", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Location notice", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Location permission denied.", substring = true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Blank radar does not mean clear weather.", substring = true).assertIsDisplayed()
                compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertIsDisplayed()
            }
        } } finally { LifecycleTestActivity.positionSource = null }
    }

    @Test fun navigationAndBackgroundInvalidatePendingFixWithoutResettingHistory() {
        val callbacks = mutableListOf<(PositionResult) -> Unit>()
        var permissionRequests = 0
        var allow = true
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun permission(result: (Boolean) -> Unit) { permissionRequests++; result(allow) }
            override fun locate(result: (PositionResult) -> Unit): () -> Unit { callbacks += result; return {} }
        }
        try { withRadar { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, permissionRequests)
            compose.revealMapControls()
            compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
            awaitText("Selected frame")
            for (cause in listOf("pan", "zoom", "search", "background", "unavailable", "failure", "denial")) {
                compose.revealMapControls()
                allow = cause != "denial"
                compose.onNodeWithText("Locate").performClick()
                var beforeNavigation: org.maplibre.android.camera.CameraPosition? = null
                scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    beforeNavigation = it.cameraPosition
                } }
                when (cause) {
                    "pan", "zoom" -> {
                        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                        val mapBounds = compose.onNodeWithContentDescription("Weather map").fetchSemanticsNode().boundsInWindow
                        val down = android.os.SystemClock.uptimeMillis()
                        fun event(action: Int, x: Float) {
                            android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                                x, mapBounds.center.y, 0).also {
                                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                                assertTrue(automation.injectInputEvent(it, true)); it.recycle()
                            }
                        }
                        event(android.view.MotionEvent.ACTION_DOWN, mapBounds.width * .35f)
                        if (cause == "pan") {
                            for (step in 1..10) {
                                Thread.sleep(40)
                                event(android.view.MotionEvent.ACTION_MOVE, mapBounds.width * (.35f + step * .03f))
                            }
                            event(android.view.MotionEvent.ACTION_UP, mapBounds.width * .65f)
                        } else {
                            Thread.sleep(40)
                            event(android.view.MotionEvent.ACTION_UP, mapBounds.width * .35f)
                            Thread.sleep(80)
                            event(android.view.MotionEvent.ACTION_DOWN, mapBounds.width * .35f)
                            Thread.sleep(40)
                            event(android.view.MotionEvent.ACTION_UP, mapBounds.width * .35f)
                        }
                    }
                    "search" -> scenario.onActivity { activity ->
                        activity.navigationStarted()
                        requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                            it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(35.0, -90.0), 5.0))
                        }
                    }
                    "background" -> {
                        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    }
                    "unavailable" -> scenario.onActivity { callbacks.last()(PositionResult.Unavailable) }
                    "failure" -> scenario.onActivity { callbacks.last()(PositionResult.Failed) }
                }
                try {
                    compose.waitUntil(5000) { compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty() }
                } catch (failure: AssertionError) { throw AssertionError("Pending Locate after $cause", failure) }
                // Allow the map's gesture animation to settle before checking a stale fix.
                Thread.sleep(400)
                var camera: org.maplibre.android.camera.CameraPosition? = null
                scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    camera = it.cameraPosition
                    if (cause == "pan") assertNotEquals(beforeNavigation!!.target, camera!!.target)
                    if (cause == "zoom") assertTrue(camera!!.zoom > beforeNavigation!!.zoom)
                } }
                if (cause != "denial") scenario.onActivity { callbacks.last()(PositionResult.Fix(LocalPosition(1.0, 2.0))) }
                scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    assertEquals("Camera preserved after $cause", camera, it.cameraPosition)
                } }
                compose.revealMapControls()
                compose.onNodeWithText("Selected frame").assertExists()
                if (cause in listOf("unavailable", "failure", "denial"))
                    compose.onNodeWithText("explore the map manually.", substring = true).assertExists()
            }
        } } finally { LifecycleTestActivity.positionSource = null }
    }

    @Test fun locateHoldsControlsCentersOnceAndResetsHistoryWhileCancelPreservesIt() {
        val callbacks = mutableListOf<(PositionResult) -> Unit>()
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun permission(result: (Boolean) -> Unit) = result(true)
            override fun locate(result: (PositionResult) -> Unit): () -> Unit {
                callbacks += result
                return {}
            }
        }
        try { withRadar { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.revealMapControls()
            awaitText("Following latest")
            val slider = compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
            slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
            awaitText("Selected frame")
            compose.onNodeWithText("Locate").performClick()
            Thread.sleep(5500)
            compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
            scenario.onActivity { callbacks[0](PositionResult.Fix(LocalPosition(10.0, 20.0))) }
            compose.onNodeWithText("Selected frame").assertExists()
            compose.onNodeWithText("Locate").performClick()
            scenario.onActivity { callbacks[1](PositionResult.Fix(LocalPosition(40.0, -100.0))) }
            awaitText("Following latest")
            scenario.onActivity { activity ->
                requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    assertEquals(40.0, it.cameraPosition.target!!.latitude, 0.001)
                    assertEquals(-100.0, it.cameraPosition.target!!.longitude, 0.001)
                    assertEquals(9.0, it.cameraPosition.zoom, 0.001)
                }
            }
        } } finally { LifecycleTestActivity.positionSource = null }
    }

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
                compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.revealMapControls()
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
            ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.revealMapControls()
                test(scenario)
            }
        }
    }
}
