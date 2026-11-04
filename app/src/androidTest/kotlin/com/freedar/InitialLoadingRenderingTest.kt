package com.freedar

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class InitialLoadingRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun disabledSystemAnimationsKeepFourStationaryDropsInBothThemes() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val settings = AppearanceSettings(instrumentation.targetContext)
        val originalTheme = settings.theme
        val resolver = instrumentation.targetContext.contentResolver
        val originalScale = android.provider.Settings.Global.getFloat(resolver, "animator_duration_scale", 1f)
        fun scale(value: Float) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "settings put global animator_duration_scale $value")).use { it.readBytes() }
        }
        try {
            scale(0f)
            for (theme in listOf(MapTheme.LIGHT, MapTheme.DARK)) {
                settings.theme(theme)
                val response = CompletableDeferred<RadarFrame>()
                LifecycleTestActivity.provider = RadarProvider { response.await() }
                ActivityScenario.launch(LifecycleTestActivity::class.java).use {
                    compose.waitUntil(30000) {
                        compose.onAllNodesWithContentDescription("Loading map imagery").fetchSemanticsNodes().isNotEmpty()
                    }
                    val bounds = compose.onNodeWithContentDescription("Loading map imagery").fetchSemanticsNode().boundsInWindow
                    fun pixels(): IntArray {
                        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        return IntArray(bounds.width.toInt() * bounds.height.toInt()).also { pixels ->
                            screenshot.getPixels(pixels, 0, bounds.width.toInt(), bounds.left.toInt(), bounds.top.toInt(),
                                bounds.width.toInt(), bounds.height.toInt())
                            screenshot.recycle()
                        }
                    }
                    val first = pixels()
                    Thread.sleep(350)
                    assertArrayEquals("Reduced motion is stationary", first, pixels())
                    val background = android.graphics.Color.red(first.first())
                    val columns = (0 until bounds.width.toInt()).filter { x ->
                        (0 until bounds.height.toInt()).any { y ->
                            val red = android.graphics.Color.red(first[y * bounds.width.toInt() + x])
                            if (theme == MapTheme.DARK) red > background + 20 else red < background - 20
                        }
                    }
                    val groups = columns.filterIndexed { i, x -> i == 0 || x > columns[i - 1] + 1 }
                    assertEquals("Four separate drops in $theme", 4, groups.size)
                    val maxContrast = first.maxOf { kotlin.math.abs(android.graphics.Color.red(it) - background) }
                    val availableContrast = if (theme == MapTheme.DARK) 255 - background else background
                    assertTrue("Drops are visible", maxContrast > availableContrast * .5f)
                    assertTrue("Opacity capped at 60 percent", maxContrast <= availableContrast * .6f + 2)
                }
            }
        } finally { scale(originalScale); settings.theme(originalTheme) }
    }

    @Test fun pendingStartupIsCenteredAccessibleAndPanelsDoNotRestartItsDelay() {
        val response = CompletableDeferred<RadarFrame>()
        LifecycleTestActivity.provider = RadarProvider { response.await() }
        ActivityScenario.launch(LifecycleTestActivity::class.java).use { scenario ->
            compose.waitUntil(30000) {
                compose.onAllNodesWithContentDescription("Loading map imagery").fetchSemanticsNodes().isNotEmpty()
            }
            val loading = compose.onNodeWithContentDescription("Loading map imagery")
            loading.assertIsDisplayed().assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(48f))
            val node = loading.fetchSemanticsNode()
            assertFalse(node.config.contains(SemanticsProperties.LiveRegion))
            assertFalse(node.config.contains(SemanticsActions.OnClick))
            val map = compose.onNodeWithContentDescription("Weather map").fetchSemanticsNode().boundsInRoot
            assertEquals(map.center.x, node.boundsInRoot.center.x, 1f)
            assertEquals(map.center.y, node.boundsInRoot.center.y, 1f)
            val tapped = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                fun findMap(view: android.view.View): org.maplibre.android.maps.MapView? {
                    if (view is org.maplibre.android.maps.MapView) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                        findMap(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                requireNotNull(findMap(activity.window.decorView)).getMapAsync { native ->
                    native.addOnMapClickListener { tapped.countDown(); true }
                }
            }
            loading.performTouchInput { click() }
            assertTrue("Touch passes through to the native map", tapped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            for (panel in listOf("Search", "Settings", "Info", "Attributions")) {
                compose.onNodeWithContentDescription(panel).performClick()
                loading.assertDoesNotExist()
                compose.onNodeWithContentDescription(panel).performClick()
                loading.assertIsDisplayed()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(30000) {
                compose.onAllNodesWithContentDescription("Loading map imagery").fetchSemanticsNodes().isNotEmpty()
            }
            response.completeExceptionally(java.io.IOException("Fixture unavailable"))
            compose.waitUntil(10000) {
                compose.onAllNodesWithContentDescription("Loading map imagery").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Info").performClick()
            compose.onNodeWithText("Radar unavailable").assertExists()
        }
    }
}
