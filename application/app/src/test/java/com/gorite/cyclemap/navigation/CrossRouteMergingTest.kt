package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RouteSegmentGrade
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossRouteMergingTest {
    @Test fun mergesInstructionIndicesAndDistanceWithoutDuplicatePortal() {
        val a = listOf(instruction(1, 100.0), instruction(2, 200.0))
        val b = listOf(instruction(0, 0.0), instruction(1, 50.0), instruction(3, 150.0))

        val merged = mergeInstructionParts(a, b, aNodeCount = 3, aDistanceMeters = 200.0)

        assertEquals(listOf(1, 2, 3, 5), merged.map { it.routeIndex })
        assertEquals(listOf(100.0, 200.0, 250.0, 350.0), merged.map { it.distanceFromStartMeters })
    }

    @Test fun mergesGradeAndElevationPartsAtOneSharedPortal() {
        val merged = mergeElevationParts(
            listOf(RouteSegmentGrade(10.0, 1.0)), listOf(10.0, 11.0),
            listOf(RouteSegmentGrade(20.0, -2.0), RouteSegmentGrade(30.0, null)), listOf(11.0, 9.0, null),
        )

        assertEquals(3, merged.segments.size)
        assertEquals(4, merged.nodeElevations.size)
        assertEquals(listOf(10.0, 11.0, 9.0, null), merged.nodeElevations)
        assertEquals(merged.nodeElevations.size - 1, merged.segments.size)
    }

    private fun instruction(index: Int, distance: Double) = RouteInstruction(
        type = InstructionType.LEFT_TURN,
        nodeId = index.toLong(),
        routeIndex = index,
        distanceFromStartMeters = distance,
        turnAngleDegrees = 90.0,
        junctionBranches = 3,
        incomingRoadType = "residential",
        outgoingRoadType = "residential",
        reason = "test",
    )
}
