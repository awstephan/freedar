package com.freedar

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toPixelMap
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

    /** Run seed and verify in separate instrumentation processes, with force-stop between. */
    @Test fun rememberedViewAcrossRealProcessRestart() {
        val phase = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("processCheck")
        org.junit.Assume.assumeTrue(phase == "seed" || phase == "verify")
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as FreedarApplication
        val check = context.getSharedPreferences("process-check", android.content.Context.MODE_PRIVATE)
        withRadar { scenario ->
            if (phase == "seed") {
                check.edit().putString("session", app.processSession).commit()
                scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(41.0, -101.0), 7.0))
                } }
                compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
                awaitText("Selected frame")
            } else {
                assertNotNull(check.getString("session", null))
                assertNotEquals(check.getString("session", null), app.processSession)
                compose.onNodeWithText("Following latest").assertExists()
                scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    assertEquals(41.0, it.cameraPosition.target!!.latitude, .001)
                    assertEquals(-101.0, it.cameraPosition.target!!.longitude, .001)
                    assertEquals(7.0, it.cameraPosition.zoom, .001)
                } }
            }
        }
        if (phase == "verify") {
            check.edit().clear().commit()
            context.getSharedPreferences("remembered-view", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun freshLaunchRemembersCameraButOpensLatestAndClearPreservesViewSettingsAndPlaces() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("remembered-view", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try { withRadar { first ->
            first.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 6.0))
            } }
            compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
            awaitText("Selected frame")
            first.onActivity {
                LifecycleTestActivity.savedForTest = it.saveBeforeStop().apply {
                    // Android can deliver an old task Bundle after process death.
                    putString("processSession", "previous-process")
                }
            }
            first.close()
            ActivityScenario.launch(LifecycleTestActivity::class.java).use { second ->
                compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.revealMapControls()
                compose.onNodeWithText("Following latest").assertExists()
                second.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    assertEquals(40.0, it.cameraPosition.target!!.latitude, .001)
                    assertEquals(-100.0, it.cameraPosition.target!!.longitude, .001)
                    assertEquals(6.0, it.cameraPosition.zoom, .001)
                } }
                val app = context.applicationContext as FreedarApplication
                app.imagery.put("fixture", "image/png", byteArrayOf(1), app.imagery.generation())
                val places = java.io.File(context.noBackupFilesDir, "place-search/preservation-check")
                places.parentFile!!.mkdirs(); places.writeText("preserve")
                val savedView = preferences.all
                try {
                    compose.onNodeWithText("Settings").performClick()
                    compose.onNodeWithText("Fast · 4 fps").performScrollTo().performClick()
                    compose.onNodeWithText("Clear downloaded data").performScrollTo().performClick()
                    compose.waitUntil(5000) { compose.onAllNodesWithText("Downloaded imagery cleared").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(0L, app.imagery.size())
                    assertEquals("preserve", places.readText())
                    assertEquals(savedView, preferences.all)
                    compose.onNodeWithText("Fast · 4 fps").performScrollTo().assertIsSelected()
                } finally { places.delete() }
            }
        } } finally {
            LifecycleTestActivity.savedForTest = null
            preferences.edit().clear().commit()
            context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun themesPersistAndChangeNativeBasemapAndComposeWithoutLosingSelectionOrCamera() {
        val preferences = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try { withRadar { scenario ->
            compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
            awaitText("Selected frame")
            scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                it.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.0, -100.0), 6.0))
            } }
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("System").performScrollTo().assertIsSelected()
            for ((choice, dark) in listOf("Dark" to true, "Light" to false, "System" to false)) {
                var previous: LifecycleTestActivity? = null
                scenario.onActivity { previous = it }
                compose.onNodeWithText(choice).performScrollTo().performClick()
                compose.waitUntil(10000) {
                    var ready = false
                    scenario.onActivity { ready = it !== previous && it.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED }
                    ready
                }
                compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(choice).performScrollTo().assertIsSelected()
                scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync { map ->
                    val expectedDark = if (choice == "System") activity.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES else dark
                    val background = map.style!!.getLayerAs<org.maplibre.android.style.layers.BackgroundLayer>("background")!!
                    val color = background.backgroundColorAsInt
                    assertEquals(expectedDark, Color.red(color) < 128)
                    assertEquals(40.0, map.cameraPosition.target!!.latitude, .001)
                    assertEquals(6.0, map.cameraPosition.zoom, .001)
                } }
                compose.onNodeWithText("Done").performScrollTo().performClick()
                compose.onNodeWithText("Selected frame").assertExists()
                val image = compose.onNodeWithText("Settings").captureToImage()
                val pixel = image.toPixelMap()[0, 0]
                if (choice != "System") assertEquals(dark, pixel.red < .5f)
                compose.onNodeWithText("Settings").performClick()
            }
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val originalNight = instrumentation.targetContext.getSystemService(android.app.UiModeManager::class.java).nightMode
            fun night(mode: String) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                    .executeShellCommand("cmd uimode night $mode")).use { it.readBytes() }
            }
            try {
                for (dark in listOf(true, false)) {
                    night(if (dark) "yes" else "no")
                    compose.waitUntil(15000) {
                        var applied = false
                        scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync { map ->
                            val background = map.style?.getLayerAs<org.maplibre.android.style.layers.BackgroundLayer>("background")
                            applied = background != null && (Color.red(background.backgroundColorAsInt) < 128) == dark
                        } }
                        applied
                    }
                    compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("System").performScrollTo().assertIsSelected()
                    compose.onNodeWithText("Done").performScrollTo().performClick()
                    val pixel = compose.onNodeWithText("Settings").captureToImage().toPixelMap()[0, 0]
                    assertEquals(dark, pixel.red < .5f)
                    compose.onNodeWithText("Settings").performClick()
                }
            } finally { night(when (originalNight) { android.app.UiModeManager.MODE_NIGHT_YES -> "yes"; android.app.UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" }) }
        } } finally { preferences.edit().clear().commit() }
    }

    @Test fun legendAndSettingsStayOpenAcrossTimeoutAndLargeTextRotationWithCredits() = withLargeText {
        withRadar { scenario ->
            compose.onNodeWithText("Legend").performScrollTo().performClick()
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                Thread.sleep(5500)
                compose.onNodeWithText("Rain · dBZ samples").performScrollTo().assertIsDisplayed()
                val rain = compose.onNodeWithContentDescription("Rain, 35 dBZ").performScrollTo().captureToImage().toPixelMap()[10, 10]
                assertEquals(1f, rain.red, .01f)
                assertEquals(238f / 255f, rain.green, .01f)
                assertEquals(0f, rain.blue, .01f)
                compose.onNodeWithText("Snow · dBZ samples").performScrollTo().assertIsDisplayed()
                val snow = compose.onNodeWithContentDescription("Snow, 35 dBZ").performScrollTo().captureToImage().toPixelMap()[10, 10]
                assertEquals(63f / 255f, snow.red, .01f)
                assertEquals(127f / 255f, snow.green, .01f)
                assertEquals(1f, snow.blue, .01f)
                compose.onNodeWithText("Frame time", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Blank radar does not mean clear weather.", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Places: GeoNames", substring = true).assertIsDisplayed()
            }
            compose.onNodeWithText("Close legend").performScrollTo().performClick()
            compose.onNodeWithText("Settings").performScrollTo().performClick()
            Thread.sleep(5500)
            compose.onNodeWithText("About Freedar").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME}").assertExists()
            compose.onNodeWithText("GitHub project").assertExists()
            compose.onNodeWithText("GeoNames · CC BY 4.0", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Done").performScrollTo().performClick()
        }
    }

    @Test fun opacityPreviewsOnRadarPersistsAndResetsWhileSpeedReachesPlayback() {
        val preferences = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try { withRadar { scenario ->
            fun assertOpacity(expected: Float) {
                scenario.onActivity { activity -> requireNotNull(findMap(activity.window.decorView)).getMapAsync { map ->
                    val radar = map.style!!.layers.filterIsInstance<org.maplibre.android.style.layers.RasterLayer>()
                        .last { it.id.startsWith("radar-") }
                    assertEquals(expected, radar.rasterOpacity.value!!, .001f)
                } }
            }
            assertOpacity(.65f)
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithContentDescription("Radar opacity").performScrollTo()
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.3f) }
            compose.waitForIdle()
            assertOpacity(.3f)
            compose.onNodeWithText("Fast · 4 fps").performScrollTo().performClick()
            scenario.recreate()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertOpacity(.3f)
            compose.onNodeWithText("Fast · 4 fps").performScrollTo().assertIsSelected()
            compose.onNodeWithText("Reset opacity to 65%").performScrollTo().performClick()
            compose.waitForIdle()
            assertOpacity(.65f)
            compose.onNodeWithText("Done").performScrollTo().performClick()
            compose.onNodeWithText("Play").performScrollTo().performClick()
            compose.onNodeWithText("4 fps").assertExists()
        } } finally { preferences.edit().clear().commit() }
    }

    @Test fun searchEmptyResultsAndRecreationKeepManualNavigationAndLargeTextContext() = withLargeText {
        withRadar { scenario ->
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.revealMapControls()
                compose.onNodeWithText("Search").performScrollTo().performClick()
                Thread.sleep(5500)
                compose.onNodeWithText("Close search").assertExists()
                compose.onNodeWithText("City or town").performScrollTo().performClick()
                compose.waitUntil(5000) {
                    var keyboardVisible = false
                    scenario.onActivity { keyboardVisible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
                    keyboardVisible
                }
                compose.onNodeWithText("City or town").assertIsDisplayed().performTextInput("zzzznonexistentsettlement")
                compose.onNodeWithText("City or town").performImeAction()
                compose.waitUntil(30000) { compose.onAllNodesWithText("No places found.", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("No places found.", substring = true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Frame time", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Blank radar does not mean clear weather.", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Places: GeoNames", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Close search").performScrollTo().performClick()
            }
            compose.revealMapControls()
            compose.onNodeWithText("Search").performScrollTo().performClick()
            compose.onNodeWithText("City or town").performTextInput("Twizel")
            compose.rotateAndWait(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Close search").assertExists()
            compose.onNodeWithText("City or town").assertTextContains("Twizel")
            compose.onNodeWithText("City or town").performImeAction()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Twizel ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Twizel ·", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Close search").performScrollTo().performClick()
            compose.waitUntil(8000) { compose.onAllNodesWithText("Search").fetchSemanticsNodes().isEmpty() }
            compose.revealMapControls()
            compose.onNodeWithText("Search").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun submittedSearchStaysOpenAndSelectionInvalidatesLocateAndResetsHistory() {
        var lateFix: ((PositionResult) -> Unit)? = null
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun permission(result: (Boolean) -> Unit) = result(true)
            override fun locate(result: (PositionResult) -> Unit): () -> Unit {
                lateFix = result
                return {}
            }
        }
        try { withRadar { scenario ->
            compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
            awaitText("Selected frame")
            compose.onNodeWithText("Locate").performClick()
            compose.onNodeWithText("Search").performClick()
            compose.onNodeWithText("City or town").performTextInput("Reykjavik")
            Thread.sleep(5500)
            compose.onNodeWithText("Close search").assertIsDisplayed()
            compose.onNodeWithText("Reykjavík", substring = true).assertDoesNotExist()
            compose.onNodeWithText("City or town").performImeAction()
            compose.waitUntil(30000) {
                compose.onAllNodesWithText("Reykjavík", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Reykjavík", substring = true).performScrollTo().performClick()
            awaitText("Following latest")
            compose.waitUntil(30000) {
                compose.onNodeWithContentDescription("Radar history; scrubbing pauses playback")
                    .fetchSemanticsNode().config[SemanticsProperties.StateDescription].startsWith("Frame 2 of 2")
            }
            compose.onNodeWithText("Play").assertExists()
            compose.onNodeWithText("Close search").assertDoesNotExist()
            compose.onNodeWithText("Cancel").assertDoesNotExist()
            scenario.onActivity { activity ->
                lateFix!!(PositionResult.Fix(LocalPosition(1.0, 2.0)))
                requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    assertEquals(64.1355, it.cameraPosition.target!!.latitude, 0.01)
                    assertEquals(-21.8954, it.cameraPosition.target!!.longitude, 0.01)
                    assertEquals(9.0, it.cameraPosition.zoom, 0.001)
                }
            }
        } } finally { LifecycleTestActivity.positionSource = null }
    }

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
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {
                        // Choose a distinct endpoint in the same UI action. Playback
                        // can advance while scroll-to completes; the current value is a no-op.
                        assertTrue("Scrub must move the slider", it(0f) || it(1f))
                    }
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
            withFreshMapRequestBudget {
                ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                    compose.waitUntil(30000) { compose.onAllNodesWithText("Frame time", substring = true).fetchSemanticsNodes().isNotEmpty() }
                    compose.revealMapControls()
                    test(scenario)
                }
            }
        }
    }
}
