package com.freedar

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.freedar.radar.RadarProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class MapControlsRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val preferences = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("appearance", 0)
    private var originalAutoHide: Boolean? = null

    @Before fun enableAutoHide() {
        originalAutoHide = preferences.all["auto-hide-controls"] as? Boolean
        preferences.edit().putBoolean("auto-hide-controls", true).commit()
    }

    @After fun restoreAutoHide() {
        val edit = preferences.edit()
        originalAutoHide?.let { edit.putBoolean("auto-hide-controls", it) } ?: edit.remove("auto-hide-controls")
        edit.commit()
    }

    @Test fun persistentCornerIconsFitSystemInsetsAtLargeTextInBothOrientations() = withLargeText {
        preferences.edit().putBoolean("auto-hide-controls", false).commit()
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                Thread.sleep(5500)
                for (label in listOf("Attributions", "Info", "Locate", "Search", "Settings")) {
                    val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHasClickAction()
                        .fetchSemanticsNode().boundsInWindow
                    val minimumTarget = with(compose.density) { 48.dp.roundToPx() }
                    scenario.onActivity {
                        assertEquals(1.8f, it.resources.configuration.fontScale, .01f)
                        val decor = it.window.decorView
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
                            .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        assertTrue(bounds.top >= insets.top)
                        assertTrue(bounds.bottom <= decor.height - insets.bottom)
                        assertTrue(bounds.left >= insets.left)
                        assertTrue(bounds.right <= decor.width - insets.right)
                        assertTrue("$label height ${bounds.height} must fit a 48 dp target ($minimumTarget px)",
                            bounds.height >= minimumTarget)
                        assertTrue("$label width ${bounds.width} must fit a 48 dp target ($minimumTarget px)",
                            bounds.width >= minimumTarget)
                    }
                }
                compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertDoesNotExist()
                compose.onNodeWithContentDescription("Info").performClick()
                compose.onNodeWithText("Radar unavailable").assertExists()
                compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertDoesNotExist()
                compose.onNodeWithText("Rain · dBZ samples").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Close info").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Radar opacity", substring = true).assertExists()
                compose.onNodeWithText("Auto-hide controls").performScrollTo().assertIsOff()
                // Persistent corner actions switch directly, rather than queuing hidden panels.
                compose.onNodeWithContentDescription("Info").performClick()
                compose.onNodeWithText("Close info").assertIsDisplayed()
                compose.onNodeWithText("Done").assertDoesNotExist()
                compose.onNodeWithText("Close info").performClick()
                for ((label, closeLabel) in listOf("Attributions" to "Close attributions", "Info" to "Close info",
                    "Settings" to "Done", "Search" to "Close search")) {
                    // Exercise the invisible target beyond the 28 dp visual background.
                    compose.onNodeWithContentDescription(label).performTouchInput { click(Offset(1f, center.y)) }
                    compose.onNodeWithText(closeLabel).assertExists()
                    if (label == "Attributions") {
                        compose.onNodeWithText("© OpenStreetMap contributors").performScrollTo().assertIsDisplayed()
                        compose.onNodeWithText("Radar status").assertDoesNotExist()
                    }
                    if (label == "Search") {
                        compose.onNodeWithText("City or town").performScrollTo().performClick().performTextInput("London")
                        compose.waitForIdle()
                    }
                    compose.onNodeWithContentDescription(label).performTouchInput {
                        click(Offset(width - 2f, center.y))
                    }
                    compose.onNodeWithText(closeLabel).assertDoesNotExist()
                }
            }
        }
    }

    @Test fun idleControlsHideMapTouchRevealsAndTheToggleSurvivesRecreation() {
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
            compose.waitForControlsIdle()
            compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
            compose.onNodeWithContentDescription("Weather map").performTouchInput { click() }
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
            compose.waitForControlsIdle()
            compose.onNodeWithText("Auto-hide controls").assertIsOn().performClick().assertIsOff()
            compose.onNodeWithText("Done").performClick()
            scenario.recreate()
            compose.waitForControlsIdle()
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed().performClick()
            compose.onNodeWithText("Auto-hide controls").assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText("Done").performClick()
            compose.waitForControlsIdle()
            compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        }
    }

    @Test fun outsideTouchDismissesPanelAndStillReachesNativeMap() {
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            val outsideY = with(compose.density) { 48.dp.toPx() }
            val tapped = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                fun findMap(view: android.view.View): org.maplibre.android.maps.MapView? {
                    if (view is org.maplibre.android.maps.MapView) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                        findMap(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                requireNotNull(findMap(activity.window.decorView)).getMapAsync {
                    it.addOnMapClickListener { tapped.countDown(); true }
                }
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Auto-hide controls").performTouchInput { click() }
            compose.onNodeWithText("Done").assertExists()
            compose.onNodeWithContentDescription("Weather map").performTouchInput {
                click(Offset(center.x, outsideY))
            }
            compose.onNodeWithText("Done").assertDoesNotExist()
            assertTrue("Outside tap reaches native map", tapped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.onNodeWithContentDescription("Info").performTouchInput { click() }
            compose.onNodeWithContentDescription("Settings").performTouchInput { click() }
            compose.onNodeWithText("Close info").assertDoesNotExist()
            compose.onNodeWithText("Done").assertExists()
            compose.onNodeWithContentDescription("Settings").performTouchInput { click() }
            compose.onNodeWithText("Done").assertDoesNotExist()
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithContentDescription("Weather map").performTouchInput {
                swipe(Offset(center.x, outsideY), Offset(center.x + 100f, outsideY), 500)
            }
            compose.onNodeWithText("Done").assertDoesNotExist()
        }
    }

    @Test fun warningIconExportsFailureAndTouchExplorationCanOpenDetails() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val original = automation.serviceInfo
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
                compose.waitUntil(30000) {
                    compose.onNodeWithContentDescription("Info").fetchSemanticsNode()
                        .config[SemanticsProperties.StateDescription].contains("Refresh failed")
                }
                fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
                    listOf(node) + (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::nodes)
                val node = nodes(automation.rootInActiveWindow).first { it.contentDescription?.toString() == "Info" }
                val info = generateSequence(node) { it.parent }.first { it.refresh(); it.isClickable }
                assertTrue(info.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(info.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithText("Retry").assertExists()
                scenario.recreate()
                compose.onNodeWithText("Close info").assertExists().performClick()
                Thread.sleep(5500)
                compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
            }
        } finally { automation.serviceInfo = original }
    }
}
