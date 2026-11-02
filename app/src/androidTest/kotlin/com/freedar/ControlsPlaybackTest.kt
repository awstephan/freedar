package com.freedar

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import java.io.ByteArrayOutputStream
import java.time.Instant
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

class ControlsPlaybackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun icon(label: String) = compose.onNodeWithContentDescription(label)
    private fun slider() = icon("Radar history; scrubbing pauses playback")
    private fun mode(value: String) = compose.waitUntil(30000) {
        slider().fetchSemanticsNode().config[SemanticsProperties.StateDescription].contains(value)
    }
    private fun selectOld() {
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        mode("Selected frame")
        compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Frame time").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun compactTimelineShowsOnlyDisplayedHistoricalTimeAndSurvivesRotation() = withLargeText {
        withRadar { scenario ->
            mode("Following latest")
            icon("Frame time").assertDoesNotExist()
            icon("Latest").assertDoesNotExist()
            selectOld()
            val historical = slider().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                compose.awaitRadar()
                assertEquals(historical, slider().fetchSemanticsNode().config[SemanticsProperties.StateDescription])
                compose.waitUntil(30000) { icon("Frame time").isDisplayed() }
                icon("Frame time").assertIsDisplayed()
                icon("Latest").assertIsDisplayed()
                icon("Play").assertIsDisplayed()
                slider().assertIsDisplayed()
            }
            icon("Play").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty() }
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                compose.awaitRadar()
                icon("Pause").assertIsDisplayed()
            }
            Thread.sleep(5500)
            icon("Pause").assertIsDisplayed().performClick()
            icon("Latest").performClick()
            mode("Following latest")
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Frame time").fetchSemanticsNodes().isEmpty() }
            icon("Play").assertIsDisplayed()
        }
    }

    @Test fun deniedFirstOpenUsesContiguousUsAndGrantedColdLaunchLocatesButRecreationDoesNot() {
        val preferences = context.getSharedPreferences("remembered-view", 0)
        preferences.edit().clear().commit()
        context.getSharedPreferences("startup", 0).edit().clear().commit()
        var granted = false
        var prompts = 0
        val fixes = mutableListOf<(PositionResult) -> Unit>()
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun hasPermission() = granted
            override fun permission(result: (Boolean) -> Unit) { prompts++; result(granted) }
            override fun locate(result: (PositionResult) -> Unit): () -> Unit { fixes += result; return {} }
        }
        try { withRadar { first ->
            assertEquals(0, prompts)
            assertTrue(fixes.isEmpty())
            first.onActivity { activity -> findMap(activity.window.decorView)!!.getMapAsync {
                val bounds = it.projection.visibleRegion.latLngBounds
                assertTrue(bounds.contains(LatLng(49.0, -124.0)))
                assertTrue(bounds.contains(LatLng(25.0, -67.0)))
                assertTrue(it.cameraPosition.target!!.longitude in -100.0..-90.0)
            } }
            first.close()
            granted = true
            ActivityScenario.launch(LifecycleTestActivity::class.java).use { second ->
                compose.awaitRadar()
                assertEquals(1, fixes.size)
                assertEquals(0, prompts)
                second.onActivity { fixes.last()(PositionResult.Fix(LocalPosition(30.0, -97.0))) }
                assertCamera(second, 30.0, -97.0, 9.0)
                second.recreate()
                compose.awaitRadar()
                assertEquals(1, fixes.size)
                assertCamera(second, 30.0, -97.0, 9.0)
            }
        } } finally {
            LifecycleTestActivity.positionSource = null
            preferences.edit().clear().commit()
        }
    }

    @Test fun searchCancelsLateLocationAndReturnsToLatest() {
        var lateFix: ((PositionResult) -> Unit)? = null
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun permission(result: (Boolean) -> Unit) = result(true)
            override fun locate(result: (PositionResult) -> Unit): () -> Unit { lateFix = result; return {} }
        }
        try { withRadar { scenario ->
            selectOld()
            icon("Locate").performClick()
            icon("Cancel").assertIsDisplayed()
            icon("Search").performClick()
            compose.onNodeWithText("City or town").performTextInput("Reykjavik")
            compose.onNodeWithText("City or town").performImeAction()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Reykjavík", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Reykjavík", substring = true).performScrollTo().performClick()
            mode("Following latest")
            scenario.onActivity { lateFix!!(PositionResult.Fix(LocalPosition(1.0, 2.0))) }
            assertCamera(scenario, 64.1355, -21.8954, 9.0)
            icon("Cancel").assertDoesNotExist()
        } } finally { LifecycleTestActivity.positionSource = null }
    }

    @Test fun cancelledAndBackgroundLocationCannotRecenterAndFailuresAreInInfo() {
        val fixes = mutableListOf<(PositionResult) -> Unit>()
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun permission(result: (Boolean) -> Unit) = result(true)
            override fun locate(result: (PositionResult) -> Unit): () -> Unit { fixes += result; return {} }
        }
        try { withRadar { scenario ->
            scenario.onActivity { findMap(it.window.decorView)!!.getMapAsync { map ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 6.0))
            } }
            selectOld()
            for (cause in listOf("cancel", "pan", "zoom", "background", "failure")) {
                icon("Locate").performClick()
                when (cause) {
                    "cancel" -> icon("Cancel").performClick()
                    "pan", "zoom" -> {
                        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                        val bounds = icon("Weather map").fetchSemanticsNode().boundsInWindow
                        val down = android.os.SystemClock.uptimeMillis()
                        fun event(action: Int, x: Float) {
                            android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                                x, bounds.center.y, 0).also {
                                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                                assertTrue(automation.injectInputEvent(it, true)); it.recycle()
                            }
                        }
                        event(android.view.MotionEvent.ACTION_DOWN, bounds.width * .35f)
                        if (cause == "pan") {
                            for (step in 1..10) {
                                Thread.sleep(40)
                                event(android.view.MotionEvent.ACTION_MOVE, bounds.width * (.35f + step * .03f))
                            }
                            event(android.view.MotionEvent.ACTION_UP, bounds.width * .65f)
                        } else {
                            Thread.sleep(40)
                            event(android.view.MotionEvent.ACTION_UP, bounds.width * .35f)
                            Thread.sleep(80)
                            event(android.view.MotionEvent.ACTION_DOWN, bounds.width * .35f)
                            Thread.sleep(40)
                            event(android.view.MotionEvent.ACTION_UP, bounds.width * .35f)
                        }
                    }
                    "background" -> {
                        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                        compose.awaitRadar()
                    }
                    "failure" -> scenario.onActivity { fixes.last()(PositionResult.Unavailable) }
                }
                compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Cancel").fetchSemanticsNodes().isEmpty() }
                Thread.sleep(400)
                var camera: org.maplibre.android.camera.CameraPosition? = null
                scenario.onActivity { activity -> findMap(activity.window.decorView)!!.getMapAsync { camera = it.cameraPosition } }
                scenario.onActivity { fixes.last()(PositionResult.Fix(LocalPosition(1.0, 2.0))) }
                assertCamera(scenario, camera!!.target!!.latitude, camera!!.target!!.longitude, camera!!.zoom)
                mode("Selected frame")
            }
            icon("Info").performClick()
            compose.onNodeWithText("Location unavailable.", substring = true).assertExists()
        } } finally { LifecycleTestActivity.positionSource = null }
    }

    @Test fun opacitySpeedAndThemePersistWithCameraAndTimeline() {
        val preferences = context.getSharedPreferences("appearance", 0)
        preferences.edit().clear().commit()
        try { withRadar { scenario ->
            selectOld()
            scenario.onActivity { findMap(it.window.decorView)!!.getMapAsync { map ->
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 6.0))
            } }
            icon("Settings").performClick()
            icon("Radar opacity").performSemanticsAction(SemanticsActions.SetProgress) { it(.3f) }
            compose.onNodeWithText("Fast · 4 fps").performScrollTo().performClick()
            for ((theme, color) in listOf("Dark" to Color.rgb(51, 51, 51), "Light" to Color.rgb(233, 238, 236))) {
                compose.onNodeWithText(theme).performScrollTo().performClick()
                compose.waitUntil(30000) {
                    var ready = false
                    scenario.onActivity { findMap(it.window.decorView)!!.getMapAsync { map ->
                        val background = map.style?.getLayerAs<org.maplibre.android.style.layers.BackgroundLayer>("background")
                        ready = background?.backgroundColorAsInt == color && map.style?.layers?.any { it.id.startsWith("radar-") } == true
                    } }
                    ready
                }
                compose.onNodeWithText("Fast · 4 fps").performScrollTo().assertIsSelected()
                scenario.onActivity { findMap(it.window.decorView)!!.getMapAsync { map ->
                    val radar = map.style!!.layers.filterIsInstance<org.maplibre.android.style.layers.RasterLayer>().last { it.id.startsWith("radar-") }
                    assertEquals(.3f, radar.rasterOpacity.value!!, .001f)
                } }
                assertCamera(scenario, 40.0, -100.0, 6.0)
            }
            compose.onNodeWithText("Reset opacity to 65%").performScrollTo().performClick()
            compose.onNodeWithText("Done").performScrollTo().performClick()
            mode("Selected frame")
        } } finally { preferences.edit().clear().commit() }
    }

    @Test fun clearDownloadedDataPreservesViewPreferencesAndBundledSearch() = withRadar { scenario ->
        val app = context.applicationContext as FreedarApplication
        app.imagery.put("fixture", "image/png", byteArrayOf(1), app.imagery.generation())
        PlaceSearch(app).use { assertEquals(3413829L, it.search("Reykjavik").first().id) }
        icon("Settings").performClick()
        compose.onNodeWithText("Fast · 4 fps").performScrollTo().performClick()
        val view = context.getSharedPreferences("remembered-view", 0).all
        compose.onNodeWithText("Clear downloaded data").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Downloaded imagery cleared").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0L, app.imagery.size())
        assertEquals(view, context.getSharedPreferences("remembered-view", 0).all)
        compose.onNodeWithText("Fast · 4 fps").performScrollTo().assertIsSelected()
        PlaceSearch(app).use { assertEquals(1850147L, it.search("東京").first().id) }
    }

    private fun assertCamera(scenario: ActivityScenario<LifecycleTestActivity>, lat: Double, lon: Double, zoom: Double) {
        scenario.onActivity { findMap(it.window.decorView)!!.getMapAsync { map ->
            assertEquals(lat, map.cameraPosition.target!!.latitude, .01)
            assertEquals(lon, map.cameraPosition.target!!.longitude, .01)
            assertEquals(zoom, map.cameraPosition.zoom, .001)
        } }
    }
    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findMap(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun withRadar(test: (ActivityScenario<LifecycleTestActivity>) -> Unit) {
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
            val frames = listOf(0L, 600L).map {
                RadarFrame(Instant.now().minusSeconds(600 - it), "${server.url("/")}frame$it/256/{z}/{x}/{y}/2/1_1.png")
            }
            LifecycleTestActivity.provider = object : RadarProvider {
                override suspend fun latest() = frames.last()
                override suspend fun history() = frames
            }
            LifecycleTestActivity.elapsed = 0
            withFreshMapRequestBudget {
                ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                    compose.awaitRadar()
                    test(scenario)
                }
            }
        }
    }
}
