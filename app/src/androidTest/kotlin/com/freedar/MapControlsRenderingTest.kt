package com.freedar

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ActivityInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.freedar.radar.RadarProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MapControlsRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun persistentCornerIconsFitSystemInsetsAtLargeTextInBothOrientations() = withLargeText {
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                Thread.sleep(5500)
                for (label in listOf("Info", "Locate", "Search", "Settings")) {
                    val bounds = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                    scenario.onActivity {
                        assertEquals(1.8f, it.resources.configuration.fontScale, .01f)
                        val decor = it.window.decorView
                        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
                            .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        assertTrue(bounds.top >= insets.top)
                        assertTrue(bounds.bottom <= decor.height - insets.bottom)
                        assertTrue(bounds.left >= insets.left)
                        assertTrue(bounds.right <= decor.width - insets.right)
                        assertTrue(bounds.height >= 48 * it.resources.displayMetrics.density)
                    }
                }
                compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertDoesNotExist()
                compose.onNodeWithContentDescription("Info").performClick()
                compose.onNodeWithText("Radar unavailable").assertExists()
                compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("© OpenStreetMap contributors", substring = true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Rain · dBZ samples").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Close info").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Radar opacity", substring = true).assertExists()
                compose.onNodeWithText("Keep controls visible").assertDoesNotExist()
                // Persistent corner actions switch directly, rather than queuing hidden panels.
                compose.onNodeWithContentDescription("Info").performClick()
                compose.onNodeWithText("Close info").assertIsDisplayed()
                compose.onNodeWithText("Done").assertDoesNotExist()
                compose.onNodeWithText("Close info").performClick()
            }
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
