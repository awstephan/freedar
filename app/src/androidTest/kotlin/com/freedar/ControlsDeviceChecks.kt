package com.freedar

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

fun ComposeTestRule.rotateAndWait(scenario: ActivityScenario<LifecycleTestActivity>, orientation: Int) {
    val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
    var previous: LifecycleTestActivity? = null
    var needsRecreation = false
    scenario.onActivity {
        previous = it
        needsRecreation = it.resources.configuration.orientation != expected
        it.requestedOrientation = orientation
    }
    waitUntil(10000) {
        var rotated = false
        scenario.onActivity {
            rotated = (!needsRecreation || it !== previous) &&
                it.resources.configuration.orientation == expected &&
                it.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
        }
        rotated
    }
    waitForIdle()
}

fun ComposeTestRule.revealMapControls() {
    // The center of a full-screen map node may be covered by controls or a credit
    // link at large text sizes. Invoke the explicit accessibility action instead.
    onNodeWithContentDescription("Weather map").performSemanticsAction(SemanticsActions.OnClick) { it() }
}

/** Each local provider fixture gets its own budget; recreation within it keeps that budget. */
fun withFreshMapRequestBudget(test: () -> Unit) {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FreedarApplication
    val client = app.httpClient.newBuilder().apply {
        interceptors().removeAll { it is com.freedar.radar.RadarTileRequests }
        interceptors().add(0, com.freedar.radar.RadarTileRequests())
    }.build()
    org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
    try { test() } finally { org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(app.httpClient) }
}

fun withLargeText(test: () -> Unit) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val original = Settings.System.getFloat(instrumentation.targetContext.contentResolver, "font_scale", 1f)
    fun setScale(value: Float) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "settings put system font_scale $value")).use { it.readBytes() }
    }
    try { setScale(1.8f); test() } finally { setScale(original) }
}
