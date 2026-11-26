package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.JunctionName
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteSegmentGrade
import com.gorite.cyclemap.routing.TurnClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun mergesThreeInstructionAndElevationPartsInGlobalCoordinates() {
        val instructions = mergeInstructionParts(listOf(
            CrossInstructionPart(listOf(instruction(1, 10.0)), 3, 20.0),
            CrossInstructionPart(listOf(instruction(0, 0.0), instruction(2, 30.0)), 3, 40.0),
            CrossInstructionPart(listOf(instruction(0, 0.0), instruction(1, 50.0)), 2, 50.0),
        ))
        assertEquals(listOf(1, 4, 5), instructions.map { it.routeIndex })
        assertEquals(listOf(10.0, 50.0, 110.0), instructions.map { it.distanceFromStartMeters })

        val elevation = mergeElevationParts(listOf(
            CrossElevationPart(listOf(RouteSegmentGrade(10.0, 1.0)), listOf(10.0, 11.0)),
            CrossElevationPart(listOf(RouteSegmentGrade(20.0, 2.0)), listOf(11.0, 13.0)),
            CrossElevationPart(listOf(RouteSegmentGrade(30.0, 3.0)), listOf(13.0, 16.0)),
        ))
        assertEquals(3, elevation.segments.size)
        assertEquals(listOf(10.0, 11.0, 13.0, 16.0), elevation.nodeElevations)
    }

    @Test fun portalPrefersTheOutgoingJunctionAndFallsBackToTheIncomingOne() {
        val portal = mergeInstructionParts(portalParts()).single()
        assertEquals(2L, portal.nodeId)
        assertEquals("県境東", portal.junctionName)
        assertEquals("けんきょうひがし", portal.junctionReading)
        assertEquals("県道41号", portal.outgoingRoadName)

        val incomingOnly = mergeInstructionParts(portalParts(outgoingJunction = null)).single()
        assertEquals("県境西", incomingOnly.junctionName)
        assertEquals("けんきょうにし", incomingOnly.junctionReading)
    }

    @Test fun portalSkipsANameMatchingTheRoad() {
        val portal = mergeInstructionParts(portalParts(roadName = "県境東")).single()
        assertNull(portal.junctionName)
        assertNull(portal.junctionReading)
    }

    @Test fun portalAndTurnsOnBothSidesShareOneDuplicateCheck() {
        val (incoming, outgoing) = portalParts()
        val beforePortal = instruction(1, 100.0).copy(junctionName = "県境東")
        val afterPortal = instruction(1, 11.0).copy(junctionName = "県境東")
        val merged = mergeInstructionParts(listOf(
            incoming.copy(instructions = listOf(beforePortal), nodeCount = 3),
            outgoing.copy(instructions = listOf(afterPortal), nodeCount = 3),
        ))

        assertEquals(listOf(100.0, 150.0, 161.0), merged.map { it.distanceFromStartMeters })
        assertEquals(listOf("県境東", null, "県境東"), merged.map { it.junctionName })
    }

    private fun portalParts(
        outgoingJunction: JunctionName? = JunctionName("県境東", "けんきょうひがし", 1, 7, 0.0),
        roadName: String = "県道41号",
    ): List<CrossInstructionPart> {
        val portal = RoutePoint(34.1, 131.4)
        val incoming = CrossPortalSide(
            nodeId = 2L,
            point = portal,
            adjacentNodeId = 1L,
            adjacentPoint = RoutePoint(34.099, 131.4),
            routeEdge = null,
            branches = listOf(TurnClassifier.MappedBranch(270.0, "residential", 90.0, 4L)),
            junction = JunctionName("県境西", "けんきょうにし", 1, 3, 0.0),
        )
        val outgoing = CrossPortalSide(
            nodeId = 2L,
            point = portal,
            adjacentNodeId = 3L,
            adjacentPoint = RoutePoint(34.1, 131.401),
            routeEdge = LazyMappedRoadGraph.JunctionBranch(8, "residential", 90.0, roadName = roadName),
            branches = listOf(TurnClassifier.MappedBranch(90.0, "residential", 90.0, 3L)),
            junction = outgoingJunction,
        )
        return listOf(
            CrossInstructionPart(emptyList(), 2, 150.0, exit = incoming),
            CrossInstructionPart(emptyList(), 2, 90.0, entry = outgoing),
        )
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
