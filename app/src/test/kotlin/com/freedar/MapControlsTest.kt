package com.freedar

import org.junit.Assert.*
import org.junit.Test

class MapControlsTest {
    @Test fun overridesAndHeldInteractionsPreventTimeoutAndReleaseStartsFreshInterval() {
        var now = 0L
        val controls = MapControls { now }
        for (reason in ControlsHold.entries) {
            controls.hold(reason, true)
            now += 6000
            assertTrue("Held by $reason", controls.visible)
            controls.hold(reason, false)
            now += 4999
            assertTrue(controls.visible)
            now++
            assertFalse(controls.visible)
        }
        controls.hold(ControlsHold.OVERLAY, true)
        controls.hold(ControlsHold.TOUCH_EXPLORATION, true)
        controls.hold(ControlsHold.OVERLAY, false)
        now += 10000
        assertTrue(controls.visible)
        controls.hold(ControlsHold.TOUCH_EXPLORATION, false)
        now += 5000
        assertFalse(controls.visible)
    }

    @Test fun openingAndMapTapsAllowFiveSecondsOfInactivity() {
        var now = 0L
        val controls = MapControls { now }
        assertTrue(controls.visible)
        now = 4999
        assertTrue(controls.visible)
        now = 5000
        assertFalse(controls.visible)
        controls.reveal()
        now = 9999
        assertTrue(controls.visible)
        now = 10000
        assertFalse(controls.visible)
    }
}
