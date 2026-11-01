package com.freedar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freedar.radar.RadarProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class MapControlsRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @org.junit.After fun resetPreference() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("map-controls", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun largeTextControlsRemainReachableInsideSystemInsetsInBothOrientations() = withLargeText {
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            for (orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                compose.rotateAndWait(scenario, orientation)
                compose.revealMapControls()
                val settings = compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
                val bounds = settings.fetchSemanticsNode().boundsInWindow
                scenario.onActivity {
                    assertEquals(1.8f, it.resources.configuration.fontScale, 0.01f)
                    val decor = it.window.decorView
                    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)!!
                        .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    assertTrue(bounds.top >= insets.top)
                    assertTrue(bounds.bottom <= decor.height - insets.bottom)
                    assertTrue(bounds.left >= insets.left)
                    assertTrue(bounds.right <= decor.width - insets.right)
                    assertTrue(bounds.height >= 48 * it.resources.displayMetrics.density)
                }
                settings.performClick()
                compose.onNodeWithText("Keep controls visible").assertIsDisplayed()
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithText("Retry").assertIsDisplayed()
                compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertIsDisplayed()
            }
        }
    }

    @Test fun platformTouchExplorationKeepsControlsAndSupportsAccessibilityFocusAndClick() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val original = automation.serviceInfo
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        LifecycleTestActivity.positionSource = object : ForegroundPositionSource {
            override fun permission(result: (Boolean) -> Unit) = result(true)
            override fun locate(result: (PositionResult) -> Unit): () -> Unit = {}
        }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }
            ActivityScenario.launch(LifecycleTestActivity::class.java).use {
                Thread.sleep(5500)
                compose.onNodeWithText("Settings").assertIsDisplayed()
                val readingOrder = accessibilityNodes(automation.rootInActiveWindow).map { it.text?.toString() }
                assertTrue("Status action precedes map controls", readingOrder.indexOf("Retry") in 0 until readingOrder.indexOf("Settings"))
                compose.waitUntil(30000) { compose.onAllNodes(hasText("Locate") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                val locate = accessibleButton("Locate")
                assertTrue(locate.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(locate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithText("Cancel").assertIsDisplayed()
                val cancel = accessibleButton("Cancel")
                assertTrue(cancel.isAccessibilityFocused || cancel.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithText("Locate").assertIsDisplayed()
                val search = accessibleButton("Search")
                assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(5500)
                compose.onNodeWithText("City or town").assertIsDisplayed()
                val closeSearch = accessibleButton("Close search")
                assertTrue(closeSearch.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(closeSearch.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val legend = accessibleButton("Legend")
                assertTrue(legend.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(legend.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                Thread.sleep(5500)
                compose.onNodeWithText("Rain · dBZ samples").assertIsDisplayed()
                val closeLegend = accessibleButton("Close legend")
                assertTrue(closeLegend.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(closeLegend.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val settings = accessibleButton("Settings")
                assertTrue(settings.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(settings.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithText("Keep controls visible").assertIsDisplayed()
                val done = accessibleButton("Done")
                assertTrue(done.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                assertTrue(done.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                compose.onNodeWithText("Settings").assertIsDisplayed()
                automation.serviceInfo = original
                compose.waitUntil(8000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty() }
                automation.serviceInfo = automation.serviceInfo.apply {
                    flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                }
                compose.waitUntil(5000) { compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { automation.serviceInfo = original; LifecycleTestActivity.positionSource = null }
    }

    private fun accessibleButton(text: String): AccessibilityNodeInfo =
        generateSequence(accessibleNode(text)) { it.parent }.first { it.refresh(); it.isClickable }

    private fun accessibleNode(text: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        fun dump(node: AccessibilityNodeInfo, depth: Int = 0): String =
            "${" ".repeat(depth)}${node.className} text=${node.text} description=${node.contentDescription}\n" +
                (0 until node.childCount).mapNotNull { node.getChild(it) }.joinToString("") { dump(it, depth + 1) }
        try {
            compose.waitUntil(5000) {
                result = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.let {
                    accessibilityNodes(it).firstOrNull { node -> node.text?.toString() == text }
                }
                result != null
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError(dump(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow), failure)
        }
        return requireNotNull(result)
    }

    private fun accessibilityNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        // Fetch current exported properties, including in-place Locate/Cancel label changes.
        node.refresh()
        return listOf(node) + (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::accessibilityNodes)
    }

    @Test fun timeoutKeepsHonestStatusAndCreditsAndMapTapRevealsSettings() {
        LifecycleTestActivity.provider = RadarProvider { error("Fixture unavailable") }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use {
            compose.onNodeWithText("Settings").assertExists()
            compose.waitUntil(8000) {
                compose.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("Radar unavailable").assertExists()
            compose.onNodeWithText("Retry").assertExists()
            compose.onNodeWithText("Blank radar does not mean clear weather.", substring = true).assertExists()
            compose.onNodeWithText("© OpenStreetMap contributors", substring = true).assertExists()
            compose.onNodeWithContentDescription("Weather map").performTouchInput { down(center) }
            Thread.sleep(5500)
            compose.onNodeWithText("Settings").assertExists()
            compose.onNodeWithContentDescription("Weather map").performTouchInput { up() }
            compose.onNodeWithText("Settings").performClick()
            Thread.sleep(5500)
            it.recreate()
            compose.onNodeWithText("Keep controls visible").assertExists()
            compose.onNodeWithText("Keep controls visible").performClick()
            compose.onNodeWithText("Done").performClick()
            Thread.sleep(5500)
            compose.onNodeWithText("Settings").assertExists()
            it.recreate()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Keep controls visible").assertIsOn()
            // Leave preference at its default for other activity checks.
            compose.onNodeWithText("Keep controls visible").performClick()
            compose.onNodeWithText("Done").performClick()
        }
    }
}
