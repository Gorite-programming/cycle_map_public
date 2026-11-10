package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.utils.RouteRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class GraphSwitchRouteRetryTest {
    private val request = RouteRequest(
        start = GeoPoint(34.0, 131.0),
        goal = GeoPoint(35.0, 133.0),
        isReroute = true,
        waypoints = listOf(GeoPoint(34.5, 132.0)),
        preference = null,
    )

    @Test
    fun graphSwitchRetriesAnInFlightRequestOnlyOnce() {
        assertTrue(shouldRetryAfterGraphSwitch(InFlightRouteRequest(request, 4L, retriedAfterSwitch = false)))
        assertFalse(shouldRetryAfterGraphSwitch(InFlightRouteRequest(request, 5L, retriedAfterSwitch = true)))
        assertFalse(shouldRetryAfterGraphSwitch(null))
    }

    @Test
    fun staleExecutionGenerationIsNotCurrent() {
        assertTrue(isCurrentRouteExecution(7L, 7L))
        assertFalse(isCurrentRouteExecution(6L, 7L))
    }
}
