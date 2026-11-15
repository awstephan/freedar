package com.freedar

import org.junit.Assert.*
import org.junit.Test

class MapControlsTest {
    @Test fun hidesAtFiveSecondsAndInteractionStartsAFreshInterval() {
        var time = 0L
        val controls = MapControls { time }
        time = 4999
        assertTrue(controls.visible)
        time = 5000
        assertFalse(controls.visible)
        controls.reveal()
        assertTrue(controls.visible)
        time = 9999
        assertTrue(controls.visible)
        time = 10000
        assertFalse(controls.visible)
    }

    @Test fun eachHoldPreventsHidingAndReleaseRestartsTheFullCountdown() {
        for (reason in ControlsHold.entries) {
            var time = 0L
            val controls = MapControls { time }
            controls.hold(reason, true)
            time = 20000
            assertTrue("$reason holds visibility", controls.visible)
            controls.hold(reason, false)
            time += 4999
            assertTrue(controls.visible)
            time++
            assertFalse(controls.visible)
        }
    }

    @Test fun overlappingHoldsDoNotHideUntilTheLastOneEnds() {
        var time = 0L
        val controls = MapControls { time }
        controls.hold(ControlsHold.PANEL, true)
        controls.hold(ControlsHold.LOCATING, true)
        time = 10000
        controls.hold(ControlsHold.PANEL, false)
        time = 20000
        assertTrue(controls.visible)
        controls.hold(ControlsHold.LOCATING, false)
        time = 25000
        assertFalse(controls.visible)
    }

    @Test fun repeatedUnchangedHoldsDoNotCountAsInteraction() {
        var time = 0L
        val controls = MapControls { time }
        time = 4999
        for (reason in ControlsHold.entries) controls.hold(reason, false)
        time = 5000
        assertFalse(controls.visible)
    }
}
