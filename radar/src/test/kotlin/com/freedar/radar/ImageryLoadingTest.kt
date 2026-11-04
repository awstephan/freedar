package com.freedar.radar

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ImageryLoadingTest {
    @Test fun `fast loads never flash and stale endings cannot dismiss replacement work`() = runTest {
        val indicator = ImageryLoading(backgroundScope)
        val old = indicator.begin()
        runCurrent()
        testScheduler.advanceTimeBy(199)
        indicator.end(old)
        val replacement = indicator.begin()
        runCurrent()
        testScheduler.advanceTimeBy(199)
        runCurrent()
        assertFalse(indicator.visible.value)
        testScheduler.advanceTimeBy(1)
        runCurrent()
        indicator.end(old)
        assertTrue(indicator.visible.value)
        indicator.end(replacement)
        testScheduler.advanceTimeBy(1000)
        runCurrent()
        assertFalse(indicator.visible.value)
    }

    @Test fun `one delayed indicator survives concurrent completion and disappears immediately after the last load`() = runTest {
        val indicator = ImageryLoading(backgroundScope)
        val basemap = indicator.begin()
        runCurrent()
        testScheduler.advanceTimeBy(199)
        assertFalse(indicator.visible.value)
        val radar = indicator.begin()
        testScheduler.advanceTimeBy(1)
        runCurrent()
        assertTrue(indicator.visible.value)
        indicator.end(basemap)
        assertTrue(indicator.visible.value)
        indicator.end(radar)
        assertFalse(indicator.visible.value)
    }
}
