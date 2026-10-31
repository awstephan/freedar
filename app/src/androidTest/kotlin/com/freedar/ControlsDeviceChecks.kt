package com.freedar

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry

fun ComposeTestRule.rotateAndWait(scenario: ActivityScenario<LifecycleTestActivity>, orientation: Int) {
    val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
    scenario.onActivity { it.requestedOrientation = orientation }
    waitUntil(10000) {
        var rotated = false
        scenario.onActivity { rotated = it.resources.configuration.orientation == expected }
        rotated
    }
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
