package com.freedar

import org.junit.Assert.*
import org.junit.Test

class OneShotPositioningTest {
    private class Source : ForegroundPositionSource {
        var granted = false
        override fun hasPermission() = granted
        var permissions = mutableListOf<(Boolean) -> Unit>()
        var fixes = mutableListOf<(PositionResult) -> Unit>()
        var stops = 0
        override fun permission(result: (Boolean) -> Unit) { permissions += result }
        override fun locate(result: (PositionResult) -> Unit): () -> Unit {
            fixes += result
            return { stops++ }
        }
    }

    @Test fun startupNeverPromptsAndOnlyCentersWithAnExistingGrant() {
        val source = Source()
        val navigations = mutableListOf<LocalPosition>()
        val positioning = OneShotPositioning(source, navigations::add)
        positioning.locateIfPermitted()
        assertTrue(source.permissions.isEmpty())
        assertTrue(source.fixes.isEmpty())
        source.granted = true
        positioning.locateIfPermitted()
        assertTrue(source.permissions.isEmpty())
        source.fixes.single()(PositionResult.Fix(LocalPosition(30.0, -97.0)))
        assertEquals(listOf(LocalPosition(30.0, -97.0)), navigations)
        positioning.locateIfPermitted()
        positioning.navigationStarted()
        source.fixes.last()(PositionResult.Fix(LocalPosition(31.0, -98.0)))
        assertEquals(1, navigations.size)
    }

    @Test fun cancellationNavigationAndBackgroundRejectLatePermissionAndFixes() {
        for (invalidate in listOf<(OneShotPositioning) -> Unit>(
            { it.cancel() }, { it.navigationStarted() }, { it.background() },
        )) {
            val source = Source()
            val navigations = mutableListOf<LocalPosition>()
            val locate = OneShotPositioning(source, navigations::add)
            locate.locate()
            invalidate(locate)
            source.permissions[0](true)
            assertTrue(source.fixes.isEmpty())
            locate.locate()
            source.permissions[1](true)
            invalidate(locate)
            locate.locate()
            source.permissions[2](true)
            source.fixes[0](PositionResult.Fix(LocalPosition(1.0, 2.0)))
            assertTrue(locate.state.value.waiting)
            assertTrue(navigations.isEmpty())
            source.fixes[1](PositionResult.Fix(LocalPosition(3.0, 4.0)))
            assertEquals(listOf(LocalPosition(3.0, 4.0)), navigations)
            assertEquals(2, source.stops)
        }
    }

    @Test fun denialUnavailableAndFailureExplainWithoutNavigationOrReset() {
        val source = Source()
        val locate = OneShotPositioning(source) { error("Must preserve camera and timeline") }
        locate.locate()
        source.permissions.last()(false)
        assertFalse(locate.state.value.waiting)
        assertTrue(locate.state.value.message!!.contains("permission", ignoreCase = true))
        assertTrue(source.fixes.isEmpty())
        for (result in listOf(PositionResult.Unavailable, PositionResult.Failed)) {
            locate.locate()
            source.permissions.last()(true)
            source.fixes.last()(result)
            assertFalse(locate.state.value.waiting)
            assertNotNull(locate.state.value.message)
        }
    }

    @Test fun explicitLocateRequestsPermissionAndCommitsOneFixWithLatestResetIntent() {
        val source = Source()
        val navigations = mutableListOf<LocalPosition>()
        val locate = OneShotPositioning(source, navigations::add)
        assertTrue(source.permissions.isEmpty())
        locate.locate()
        assertTrue(locate.state.value.waiting)
        source.permissions.single()(true)
        source.fixes.single()(PositionResult.Fix(LocalPosition(40.0, -100.0)))
        source.fixes.single()(PositionResult.Fix(LocalPosition(41.0, -101.0)))
        assertEquals(listOf(LocalPosition(40.0, -100.0)), navigations)
        assertFalse(locate.state.value.waiting)
        assertEquals(1, source.stops)
    }
}
