package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.MappedRouteResult
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgressIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteEndpointConnectionsTest {

    @Test
    fun crossPrefectureRouteWithoutTrimConnectsStartAndGoalWithinRange() {
        // 起点から約 45m 離れた最初のグラフノード、終点から約 45m 離れた最後のグラフノード
        val userStart = 34.3960 to 132.4590
        val firstNode = 34.3964 to 132.4590
        val midNode = 34.3964 to 132.4650
        val lastNode = 34.3964 to 132.4700
        val userGoal = 34.3960 to 132.4700

        val crossResult = MappedRouteResult(
            nodeIds = listOf(1L, 2L, 3L),
            totalDistanceMeters = 1000.0,
            totalCost = 1000.0,
            coordinates = listOf(firstNode, midNode, lastNode),
            wasCancelled = false,
            wasTruncated = false,
            nodeIndices = listOf(0, 1, 2),
        )

        val geometry = connectRouteEndpoints(
            result = crossResult,
            trim = null,
            stops = listOf(userStart, userGoal),
            enabled = true,
        )

        assertEquals(userStart, geometry.points.first())
        assertEquals(userGoal, geometry.points.last())
        assertEquals(1, geometry.nodePointOffset)
        assertTrue(geometry.headLengthMeters in 3.0..200.0)
        assertEquals(
            displayedRouteDistance(listOf(userStart, firstNode)),
            geometry.headLengthMeters,
            1e-6,
        )
    }

    @Test
    fun shiftInstructionsForHeadMatchesHeadLengthMeters() {
        val userStart = 34.3960 to 132.4590
        val firstNode = 34.3965 to 132.4590 // 約 55.6m
        val lastNode = 34.3965 to 132.4650

        val crossResult = MappedRouteResult(
            nodeIds = listOf(1L, 2L),
            totalDistanceMeters = 550.0,
            totalCost = 550.0,
            coordinates = listOf(firstNode, lastNode),
            wasCancelled = false,
            wasTruncated = false,
            nodeIndices = listOf(0, 1),
        )

        val geometry = connectRouteEndpoints(
            result = crossResult,
            trim = null,
            stops = listOf(userStart, lastNode),
            enabled = true,
        )

        val rawInstructions = listOf(
            RouteInstruction(
                type = InstructionType.RIGHT_TURN,
                nodeId = 1L,
                routeIndex = 0,
                distanceFromStartMeters = 0.0,
                turnAngleDegrees = 90.0,
                junctionBranches = 3,
                incomingRoadType = "secondary",
                outgoingRoadType = "primary",
                reason = "turn",
            ),
            RouteInstruction(
                type = InstructionType.LEFT_TURN,
                nodeId = 2L,
                routeIndex = 1,
                distanceFromStartMeters = 550.0,
                turnAngleDegrees = -90.0,
                junctionBranches = 3,
                incomingRoadType = "primary",
                outgoingRoadType = "residential",
                reason = "turn",
            ),
        )

        val shifted = shiftInstructionsForHead(rawInstructions, geometry.headLengthMeters)

        assertEquals(geometry.headLengthMeters, shifted[0].distanceFromStartMeters, 1e-6)
        assertEquals(550.0 + geometry.headLengthMeters, shifted[1].distanceFromStartMeters, 1e-6)
    }

    @Test
    fun waypointPassTrackerRemainsAccurateWithConnectedHeadSegment() {
        val userStart = 34.3960 to 132.4590
        val firstNode = 34.3964 to 132.4590 // 約 44m
        val waypointCoord = 34.3964 to 132.4620
        val lastNode = 34.3964 to 132.4660

        val crossResult = MappedRouteResult(
            nodeIds = listOf(1L, 2L, 3L),
            totalDistanceMeters = 640.0,
            totalCost = 640.0,
            coordinates = listOf(firstNode, waypointCoord, lastNode),
            wasCancelled = false,
            wasTruncated = false,
            nodeIndices = listOf(0, 1, 2),
        )

        val geometry = connectRouteEndpoints(
            result = crossResult,
            trim = null,
            stops = listOf(userStart, waypointCoord, lastNode),
            enabled = true,
        )
        val navRoute = geometry.points.map { (lat, lon) -> RoutePoint(lat, lon) }
        val index = RouteProgressIndex(navRoute)
        val tracker = WaypointPassTracker(index, listOf(RoutePoint(waypointCoord.first, waypointCoord.second)))

        // まだ出発地にいるときは未通過
        val startProgress = index.calculate(RoutePoint(userStart.first, userStart.second))
        assertEquals(0, tracker.update(RoutePoint(userStart.first, userStart.second), 10.0, startProgress))

        // 経由地を通過した先の地点では通過と判定される
        val pastWaypoint = RoutePoint(34.3964, 132.4635)
        val pastProgress = index.calculate(pastWaypoint)
        assertEquals(1, tracker.update(pastWaypoint, 10.0, pastProgress))
    }

    @Test
    fun doesNotConnectEndpointsBeyondMaxDistanceOrTooClose() {
        val farStart = 34.3900 to 132.4590 // 約 670m (> 200m)
        val firstNode = 34.3960 to 132.4590
        val lastNode = 34.3960 to 132.4650
        val nearGoal = 34.396005 to 132.4650 // 約 0.5m (< 3m)

        val result = MappedRouteResult(
            nodeIds = listOf(1L, 2L),
            totalDistanceMeters = 550.0,
            totalCost = 550.0,
            coordinates = listOf(firstNode, lastNode),
            wasCancelled = false,
            wasTruncated = false,
            nodeIndices = listOf(0, 1),
        )

        val geometry = connectRouteEndpoints(result, trim = null, stops = listOf(farStart, nearGoal), enabled = true)
        assertEquals(listOf(firstNode, lastNode), geometry.points)
        assertEquals(0, geometry.nodePointOffset)
        assertEquals(0.0, geometry.headLengthMeters, 1e-6)
    }
}
