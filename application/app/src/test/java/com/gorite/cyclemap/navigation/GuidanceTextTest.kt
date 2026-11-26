package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.IconResolver
import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.RouteInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

class GuidanceTextTest {
    @Test
    fun namedGuidanceOnlyChangesAt500Meters() {
        assertEquals("この先 本町一丁目 を右折", maneuverGuidanceText(turn, 500.0))
        for (distance in listOf(499.0, 100.0, 99.0, 30.0, 29.0, 0.0)) {
            assertEquals("本町一丁目 を右折", maneuverGuidanceText(turn, distance))
        }
        assertEquals("本町一丁目 を右斜め分岐", maneuverGuidanceText(
            turn.copy(type = InstructionType.DIAGONAL_FORK_RIGHT), 80.0,
        ))
    }

    @Test
    fun unnamedGuidanceKeepsTheExistingDistanceThresholds() {
        val unnamed = turn.copy(junctionName = null)
        assertEquals("この先 右折です", maneuverGuidanceText(unnamed, 500.0))
        assertEquals("右折です", maneuverGuidanceText(unnamed, 499.0))
        assertEquals("右折です", maneuverGuidanceText(unnamed, 100.0))
        assertEquals("まもなく右折です", maneuverGuidanceText(unnamed, 99.0))
        assertEquals("まもなく右折です", maneuverGuidanceText(unnamed, 30.0))
        assertEquals("右折です", maneuverGuidanceText(unnamed, 29.0))
        assertEquals("まもなく右折です", maneuverGuidanceText(turn, 80.0, includeJunctionName = false))
    }

    @Test
    fun abbreviatesAfterElevenCharactersOnlyWhenTheNameExceedsTwelve() {
        val twelveCharacters = "一二三四五六七八九十甲乙"
        assertEquals("$twelveCharacters を右折", maneuverGuidanceText(turn.copy(junctionName = twelveCharacters), 80.0))
        assertEquals(
            "一二三四五六七八九十甲… を右折",
            maneuverGuidanceText(turn.copy(junctionName = "${twelveCharacters}丙"), 80.0),
        )
    }

    @Test
    fun labelsThatDoNotFitTheNamedSentenceKeepTheirExistingText() {
        val types = listOf(
            InstructionType.U_TURN_LEFT, InstructionType.U_TURN_RIGHT,
            InstructionType.ROUNDABOUT, InstructionType.RAMP_ENTRY, InstructionType.RAMP_EXIT,
            InstructionType.CONSECUTIVE_FORK,
        )
        for (type in types) {
            val label = IconResolver.getLabel(type, turn.turnAngleDegrees)
            assertEquals("まもなく${label}です", maneuverGuidanceText(turn.copy(type = type), 80.0))
        }
    }

    @Test
    fun junctionShapeIsReplacedByTheName() {
        // ふつうの十字路の右折 (CROSS_JUNCTION) にも名前が出ること
        assertEquals("本町一丁目 を右折", maneuverGuidanceText(turn.copy(type = InstructionType.CROSS_JUNCTION), 80.0))
        assertEquals("本町一丁目 を左折", maneuverGuidanceText(
            turn.copy(type = InstructionType.T_JUNCTION, turnAngleDegrees = -90.0), 80.0,
        ))
        assertEquals("本町一丁目 を右斜め方向", maneuverGuidanceText(
            turn.copy(type = InstructionType.MULTI_JUNCTION, turnAngleDegrees = 30.0), 80.0,
        ))
        assertEquals("本町一丁目 を左方向", maneuverGuidanceText(
            turn.copy(type = InstructionType.Y_JUNCTION, turnAngleDegrees = -20.0), 80.0,
        ))
        // 向きの無い「T字路」は置き換えられないので今の文言
        assertEquals("まもなくT字路です", maneuverGuidanceText(
            turn.copy(type = InstructionType.T_JUNCTION, turnAngleDegrees = 0.0), 80.0,
        ))
        assertEquals("右折 本町一丁目", stepListLabel(
            upcomingSteps(listOf(turn.copy(type = InstructionType.CROSS_JUNCTION)), 0.0).single(),
        ))
    }

    @Test
    fun landscapeStreetLabelUsesTheJunctionAndTheActualRoadNameWhenAvailable() {
        assertEquals("本町一丁目 → 県道41号", landscapeStreetLabel("本町一丁目", "県道41号", "主要な道"))
        assertEquals("本町一丁目", landscapeStreetLabel("本町一丁目", null, "主要な道"))
        assertEquals("県道41号", landscapeStreetLabel(null, "県道41号", "主要な道"))
        assertEquals("主要な道", landscapeStreetLabel(null, null, "主要な道"))
    }

    @Test
    fun stepListLabelAppendsAnAvailableJunctionName() {
        assertEquals("右折 本町一丁目", stepListLabel(upcomingSteps(listOf(turn), 0.0).single()))
        assertEquals("右折", stepListLabel(upcomingSteps(listOf(turn.copy(junctionName = null)), 0.0).single()))
    }

    private val turn = RouteInstruction(
        type = InstructionType.RIGHT_TURN,
        nodeId = 1L,
        routeIndex = 1,
        distanceFromStartMeters = 100.0,
        turnAngleDegrees = 90.0,
        junctionBranches = 3,
        incomingRoadType = "residential",
        outgoingRoadType = "secondary",
        reason = "test",
        junctionName = "本町一丁目",
    )
}
