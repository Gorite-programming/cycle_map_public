package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.contract.TurnType
import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.Maneuver
import com.gorite.cyclemap.routing.ManeuverType
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RouteProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavGuidanceDisplayTest {
    private fun inst(
        type: InstructionType,
        distFromStart: Double,
        angle: Double = 0.0,
        roadName: String? = null,
    ) = RouteInstruction(
        type = type,
        nodeId = distFromStart.toLong(),
        routeIndex = 1,
        distanceFromStartMeters = distFromStart,
        turnAngleDegrees = angle,
        junctionBranches = 3,
        incomingRoadType = "secondary",
        outgoingRoadType = "residential",
        reason = "test",
        outgoingRoadName = roadName,
    )

    @Test
    fun lookaheadPastStraightUsesTurnArrowDistanceAndTextTogether() {
        val progress = RouteProgress(
            distanceFromStartMeters = 100.0,
            routeDistanceMeters = 1000.0,
            distanceToRouteMeters = 2.0,
            nextManeuver = Maneuver(ManeuverType.RIGHT, 250.0),
            distanceToNextManeuverMeters = 150.0,
        )
        val instructions = listOf(
            inst(InstructionType.STRAIGHT, 130.0),
            inst(InstructionType.RIGHT_TURN, 180.0, angle = 90.0, roadName = "相生通り"),
            inst(InstructionType.LEFT_TURN, 500.0, angle = -90.0),
        )

        val display = resolveNavGuidanceDisplay(progress, 900.0, instructions, arrivalRadiusMeters = 30.0)
        assertEquals(TurnType.Right, display.turnType)
        assertEquals(80.0, display.distanceMeters, 1e-6)
        assertEquals("まもなく右折です", display.instructionText)
        assertEquals("相生通り", display.targetInstruction?.outgoingRoadName)
        assertEquals(InstructionType.LEFT_TURN, display.nextInstruction?.type)
        assertEquals(320.0, display.distanceToNextMeters!!, 1e-6)
    }

    @Test
    fun farTurnBeyond500mShowsStraightArrowAndStraightText() {
        val progress = RouteProgress(
            distanceFromStartMeters = 0.0,
            routeDistanceMeters = 2000.0,
            distanceToRouteMeters = 0.0,
            nextManeuver = Maneuver(ManeuverType.LEFT, 800.0),
            distanceToNextManeuverMeters = 800.0,
        )
        val instructions = listOf(
            inst(InstructionType.LEFT_TURN, 800.0, angle = -90.0),
        )

        val display = resolveNavGuidanceDisplay(progress, 2000.0, instructions, arrivalRadiusMeters = 30.0)
        assertEquals(TurnType.Straight, display.turnType)
        assertEquals(800.0, display.distanceMeters, 1e-6)
        assertEquals("このまま直進です", display.instructionText)
        assertNull(display.targetInstruction)
        assertEquals(InstructionType.LEFT_TURN, display.nextInstruction?.type)
        assertEquals(800.0, display.distanceToNextMeters!!, 1e-6)
    }

    @Test
    fun tJunctionUsesTurnAngleConsistentlyAcrossArrowAndText() {
        val progress = RouteProgress(
            distanceFromStartMeters = 100.0,
            routeDistanceMeters = 1000.0,
            distanceToRouteMeters = 0.0,
            nextManeuver = Maneuver(ManeuverType.LEFT, 250.0),
            distanceToNextManeuverMeters = 150.0,
        )
        val instructions = listOf(
            inst(InstructionType.T_JUNCTION, 250.0, angle = -90.0),
        )

        val display = resolveNavGuidanceDisplay(progress, 900.0, instructions, arrivalRadiusMeters = 30.0)
        assertEquals(TurnType.Left, display.turnType)
        assertEquals(150.0, display.distanceMeters, 1e-6)
        assertEquals("T字路を左折です", display.instructionText)
    }

    @Test
    fun arrivalOverridesTurns() {
        val progress = RouteProgress(
            distanceFromStartMeters = 980.0,
            routeDistanceMeters = 1000.0,
            distanceToRouteMeters = 1.0,
            nextManeuver = Maneuver(ManeuverType.RIGHT, 990.0),
            distanceToNextManeuverMeters = 10.0,
        )
        val display = resolveNavGuidanceDisplay(progress, 20.0, emptyList(), arrivalRadiusMeters = 30.0)
        assertEquals(TurnType.Arrive, display.turnType)
        assertEquals("目的地に到着しました", display.instructionText)
    }

    @Test
    fun nearTheEndButOffRouteDoesNotClaimArrival() {
        // 並行する道を走っていて、経路への投影だけがゴールの 20 m 手前にある。
        val progress = RouteProgress(
            distanceFromStartMeters = 980.0,
            routeDistanceMeters = 1000.0,
            distanceToRouteMeters = 150.0,
            nextManeuver = null,
            distanceToNextManeuverMeters = null,
        )
        val display = resolveNavGuidanceDisplay(progress, 20.0, emptyList(), arrivalRadiusMeters = 30.0)
        assertEquals(TurnType.Arrive, display.turnType)
        assertEquals("目的地付近です", display.instructionText)
    }

    @Test
    fun fallbackWithoutRichInstructionsMatchesManeuverType() {
        val progress = RouteProgress(
            distanceFromStartMeters = 100.0,
            routeDistanceMeters = 1000.0,
            distanceToRouteMeters = 0.0,
            nextManeuver = Maneuver(ManeuverType.RIGHT, 220.0),
            distanceToNextManeuverMeters = 120.0,
        )
        val display = resolveNavGuidanceDisplay(progress, 900.0, emptyList(), arrivalRadiusMeters = 30.0)
        assertEquals(TurnType.Right, display.turnType)
        assertEquals(120.0, display.distanceMeters, 1e-6)
    }
}
