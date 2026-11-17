package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.*
import org.junit.Assert.*
import org.junit.Test

class NavigationFixPoliciesTest {
    @Test fun staleFutureDuplicateAndReversedLocationsAreRejected() {
        val now = 500_000_000_000L
        assertTrue(LocationFixPolicy.accepts(now - 1, now, null))
        assertTrue(LocationFixPolicy.accepts(now - LocationFixPolicy.MAX_AGE_NANOS, now, null))
        assertFalse(LocationFixPolicy.accepts(now - LocationFixPolicy.MAX_AGE_NANOS - 1, now, null))
        assertFalse(LocationFixPolicy.accepts(now + 1, now, null))
        assertFalse(LocationFixPolicy.accepts(now - 1, now, now))
        assertFalse(LocationFixPolicy.accepts(now, now, now))
        assertFalse(LocationFixPolicy.accepts(0, now, null))
    }

    @Test fun passedWaypointsAreRemovedFromRerouteAndRestoreInputs() {
        val route = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01))
        val waypoints = listOf(RoutePoint(0.0, 0.002), RoutePoint(0.0, 0.008))
        val tracker = WaypointPassTracker(RouteProgressIndex(route), waypoints)
        val passed = tracker.update(RoutePoint(0.0, 0.002), 10.0, null)
        assertEquals(1, passed)
        // MapScreen が保存する未通過リストを、自動・手動再探索と復元で共用する。
        val remaining = waypoints.drop(passed)
        assertEquals(listOf(waypoints.last()), remaining)
        assertEquals(1, tracker.update(RoutePoint(0.0, 0.0), 10.0, null))
        assertEquals(2, tracker.update(RoutePoint(0.0, 0.008), 10.0, null))
    }

    @Test fun progressPastWaypointAlsoMarksItPassedButEstimatedFixDoesNot() {
        val route = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01))
        val index = RouteProgressIndex(route)
        val tracker = WaypointPassTracker(index, listOf(RoutePoint(0.0, 0.002)))
        val location = RoutePoint(0.0, 0.004)
        val progress = index.calculate(location)
        assertEquals(0, tracker.update(location, 100.0, progress))
        assertEquals(1, tracker.update(location, 10.0, progress))
    }

    @Test fun crossingBackToPassedWaypointDoesNotRestoreIt() {
        val route = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01), RoutePoint(0.0, 0.0))
        val index = RouteProgressIndex(route)
        val tracker = WaypointPassTracker(index, listOf(RoutePoint(0.0, 0.002), RoutePoint(0.0, 0.008)))
        assertEquals(1, tracker.update(RoutePoint(0.0, 0.002), 5.0, null))
        val progress = index.calculate(RoutePoint(0.0, 0.002), previousProgressMeters = 2000.0, bearingDegrees = 270.0)
        assertEquals(2, tracker.update(RoutePoint(0.0, 0.002), 5.0, progress))
    }

    @Test fun borderJitterAndPoorAccuracyDoNotTriggerGraphSwitch() {
        val gate = GraphSwitchGate()
        assertFalse(gate.update("B", "A", 10.0, 1, false))
        assertFalse(gate.update("A", "A", 10.0, 2, false))
        assertFalse(gate.update("B", "A", 10.0, 3, false))
        assertFalse(gate.update("B", "A", 80.0, 4, false))
        assertFalse(gate.update("B", "A", 10.0, 5, false))
        assertFalse(gate.update("B", "A", 10.0, 6, false))
        assertFalse(gate.update("B", "A", 10.0, 6, false))
        assertTrue(gate.update("B", "A", 10.0, 7, false))
    }

    @Test fun stableBorderFixesSwitchAfterThirtySecondsAndBusyRoutesStayPinned() {
        val gate = GraphSwitchGate()
        assertFalse(gate.update("B", "A", 10.0, 1000, false))
        assertTrue(gate.update("B", "A", 10.0, 31000, false))
        // 計算中・案内中のどちらも MapGraphEffects が busy として渡す。
        assertFalse(gate.update("B", "A", 10.0, 32000, true))
        assertFalse(gate.update("B", "A", 10.0, 33000, false))
        assertFalse(gate.update("B", "A", 10.0, 34000, false))
        assertTrue(gate.update("B", "A", 10.0, 35000, false))
    }
}
