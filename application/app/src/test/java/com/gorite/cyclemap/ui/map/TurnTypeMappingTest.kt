package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.contract.TurnType
import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.RouteInstruction
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class TurnTypeMappingTest {
    private fun instruction(type: InstructionType, angle: Double) = RouteInstruction(
        type = type, nodeId = 1L, routeIndex = 1, distanceFromStartMeters = 0.0, turnAngleDegrees = angle,
        junctionBranches = 3, incomingRoadType = "residential", outgoingRoadType = "residential", reason = "test",
    )

    @Test
    fun tJunctionTurnsByAngle() {
        // 「T字路を左折」で矢印が直進にならないこと
        assertEquals(TurnType.Left, instruction(InstructionType.T_JUNCTION, -90.0).toGuidanceTurnType())
        assertEquals(TurnType.Right, instruction(InstructionType.T_JUNCTION, 88.0).toGuidanceTurnType())
    }

    @Test
    fun crossJunctionFollowsAngle() {
        assertEquals(TurnType.Left, instruction(InstructionType.CROSS_JUNCTION, -85.0).toGuidanceTurnType())
        assertEquals(TurnType.Straight, instruction(InstructionType.CROSS_JUNCTION, 4.0).toGuidanceTurnType())
        assertEquals(TurnType.SlightRight, instruction(InstructionType.Y_JUNCTION, 30.0).toGuidanceTurnType())
    }

    @Test
    fun explicitTypesKeepTheirDirection() {
        assertEquals(TurnType.Left, instruction(InstructionType.LEFT_TURN, -70.0).toGuidanceTurnType())
        assertEquals(TurnType.SharpRight, instruction(InstructionType.SHARP_RIGHT, 150.0).toGuidanceTurnType())
        assertEquals(TurnType.Roundabout, instruction(InstructionType.ROUNDABOUT, 0.0).toGuidanceTurnType())
        assertEquals(TurnType.Uturn, instruction(InstructionType.U_TURN_LEFT, -170.0).toGuidanceTurnType())
    }

    @Test
    fun nullInstructionIsStraight() {
        val none: RouteInstruction? = null
        assertEquals(TurnType.Straight, none.toGuidanceTurnType())
    }

    @Test
    fun angleBoundaries() {
        assertEquals(TurnType.Straight, turnTypeFromAngle(14.9))
        assertEquals(TurnType.SlightLeft, turnTypeFromAngle(-15.0))
        assertEquals(TurnType.Right, turnTypeFromAngle(45.0))
        assertEquals(TurnType.SharpLeft, turnTypeFromAngle(-135.0))
    }

    @Test
    fun roadClassLabels() {
        assertEquals("住宅街の道", roadClassLabel("residential"))
        assertEquals("幹線道路", roadClassLabel("primary"))
        assertNull(roadClassLabel("unknown"))
        assertNull(roadClassLabel(null))
    }

    @Test
    fun distanceIsRoundedForReadability() {
        assertEquals("20" to "m", splitDistance(24.0))
        assertEquals("10" to "m", splitDistance(3.0))
        assertEquals("250" to "m", splitDistance(237.0))
        assertEquals("1.2" to "km", splitDistance(1_240.0))
        assertEquals("950" to "m", splitDistance(974.0))
        assertEquals("1.0" to "km", splitDistance(975.0))
        assertEquals("1.0" to "km", splitDistance(999.0))
    }
}
