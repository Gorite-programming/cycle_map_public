package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.routing.Maneuver
import com.gorite.cyclemap.routing.ManeuverType
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.ui.state.DisplayMode
import com.gorite.cyclemap.ui.state.displayModeFromPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleComputerDestinationsTest {
    @Test
    fun noDestinationGivesNull() {
        assertNull(buildCycleComputerDestination(null, null, 34.0, 132.0, 0.0, null))
    }

    @Test
    fun straightLineUsesRelativeBearing() {
        // 真東に約 1 km。進行方向が北 (0°) なら、目的地は右 (約 +90°)。
        val d = buildCycleComputerDestination(34.0, 132.0108, 34.0, 132.0, 0.0, null)!!
        assertTrue(d.isStraightLine)
        assertEquals(1000.0, d.remainingMeters, 30.0)
        assertEquals(90.0, d.relativeBearingDegrees!!, 2.0)
        // 進行方向が東なら正面。
        assertEquals(0.0, buildCycleComputerDestination(34.0, 132.0108, 34.0, 132.0, 90.0, null)!!.relativeBearingDegrees!!, 2.0)
    }

    @Test
    fun unknownCourseHasNoArrow() {
        val d = buildCycleComputerDestination(34.0, 132.01, 34.0, 132.0, null, null)!!
        assertNull(d.relativeBearingDegrees)
    }

    @Test
    fun routeProgressGivesRemainingAndNextTurn() {
        val progress = RouteProgress(
            distanceFromStartMeters = 1200.0, routeDistanceMeters = 5000.0, distanceToRouteMeters = 3.0,
            nextManeuver = Maneuver(ManeuverType.RIGHT, 1500.0), distanceToNextManeuverMeters = 300.0,
        )
        val d = buildCycleComputerDestination(34.0, 132.0, 34.0, 131.9, 0.0, progress)!!
        assertFalse(d.isStraightLine)
        assertEquals(3800.0, d.remainingMeters, 0.001)
        assertEquals("右折です", d.nextTurnText)
        assertEquals(300.0, d.nextTurnDistanceM!!, 0.001)
    }

    @Test
    fun relativeBearingIsNormalized() {
        assertEquals(-170.0, normalizeRelativeBearing(190.0), 1e-9)
        assertEquals(170.0, normalizeRelativeBearing(-190.0), 1e-9)
        assertEquals(-180.0, normalizeRelativeBearing(180.0), 1e-9)
        assertEquals(0.0, normalizeRelativeBearing(360.0), 1e-9)
    }

    @Test
    fun displayModePrefFallsBackToNormal() {
        assertEquals(DisplayMode.CYCLE_COMPUTER, displayModeFromPref("CYCLE_COMPUTER"))
        assertEquals(DisplayMode.NORMAL, displayModeFromPref(null))
        assertEquals(DisplayMode.NORMAL, displayModeFromPref("SIMPLE"))
        assertEquals(DisplayMode.NORMAL, displayModeFromPref("garbage"))
        assertNotNull(DisplayMode.entries)
    }
}
