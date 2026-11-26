package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.TurnClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionCleanupTest {
    /** 紙屋町 (広島) の実データで出ていた並び: 入口の合流 → 右折 → 曲線の途中の右折 3 つ。 */
    @Test
    fun kamiyachoIntersectionBecomesASingleRightTurn() {
        val input = listOf(
            ins(InstructionType.MERGE, 512.1, 4.3, "minor-to-major"),
            ins(InstructionType.RIGHT_TURN, 527.4, 88.2, "two-branches"),
            ins(InstructionType.RIGHT_TURN, 530.0, 78.4, TurnClassifier.REASON_SINGLE_BRANCH),
            ins(InstructionType.RIGHT_TURN, 535.7, 55.4, TurnClassifier.REASON_SINGLE_BRANCH),
            ins(InstructionType.RIGHT_TURN, 539.0, 46.6, TurnClassifier.REASON_SINGLE_BRANCH),
        )
        val out = cleanUpInstructions(input)
        assertEquals(listOf(527.4), out.map { it.distanceFromStartMeters })
    }

    @Test
    fun keepsTurnFragmentsThatAreRealChoicesOrFarAwayOrTheOtherWay() {
        val first = ins(InstructionType.RIGHT_TURN, 100.0, 90.0, "two-branches")
        // 分岐のあるノード (選ぶ余地がある) は残す
        val choice = ins(InstructionType.RIGHT_TURN, 110.0, 80.0, "two-branches")
        assertEquals(2, collapseTurnFragments(listOf(first, choice)).size)
        // 30 m を超えて離れていれば残す
        val far = ins(InstructionType.RIGHT_TURN, 131.0, 80.0, TurnClassifier.REASON_SINGLE_BRANCH)
        assertEquals(2, collapseTurnFragments(listOf(first, far)).size)
        // ちょうど 30 m は同じ曲がり角の一部
        val edge = ins(InstructionType.RIGHT_TURN, 130.0, 80.0, TurnClassifier.REASON_SINGLE_BRANCH)
        assertEquals(1, collapseTurnFragments(listOf(first, edge)).size)
        // 逆向き (右折のすぐ後の左折) は残す
        val left = ins(InstructionType.LEFT_TURN, 110.0, -80.0, TurnClassifier.REASON_SINGLE_BRANCH)
        assertEquals(2, collapseTurnFragments(listOf(first, left)).size)
        // 種類が左折なら、角度の符号が右でも左として扱う (実データで見た食い違い)
        val leftPositive = ins(InstructionType.LEFT_TURN, 110.0, 15.7, TurnClassifier.REASON_SINGLE_BRANCH)
        assertEquals(2, collapseTurnFragments(listOf(first, leftPositive)).size)
    }

    @Test
    fun dropsOnlyAbsorbableMinorInstructionsJustBeforeATurn() {
        val turn = ins(InstructionType.LEFT_TURN, 200.0, -90.0, "two-branches")
        for (type in listOf(InstructionType.MERGE, InstructionType.LANE_INCREASE, InstructionType.LANE_DECREASE, InstructionType.STRAIGHT)) {
            assertEquals(listOf(turn), dropMinorBeforeTurn(listOf(ins(type, 175.0, 0.0, "x"), turn)))
        }
        // 31 m 手前なら残す
        val farMerge = ins(InstructionType.MERGE, 169.0, 0.0, "x")
        assertEquals(2, dropMinorBeforeTurn(listOf(farMerge, turn)).size)
        // 脇道への出入りは選択なので残す
        val sideRoad = ins(InstructionType.SIDE_ROAD_ENTER, 190.0, 20.0, "x")
        assertEquals(2, dropMinorBeforeTurn(listOf(sideRoad, turn)).size)
        // 後ろに曲がり角が無ければ残す
        assertEquals(1, dropMinorBeforeTurn(listOf(ins(InstructionType.MERGE, 300.0, 0.0, "x"))).size)
    }

    @Test
    fun cleanupIsIdempotent() {
        val input = listOf(
            ins(InstructionType.MERGE, 10.0, 0.0, "x"),
            ins(InstructionType.RIGHT_TURN, 20.0, 90.0, "two-branches"),
            ins(InstructionType.RIGHT_TURN, 25.0, 60.0, TurnClassifier.REASON_SINGLE_BRANCH),
            ins(InstructionType.LEFT_TURN, 300.0, -90.0, "two-branches"),
        )
        val once = cleanUpInstructions(input)
        assertEquals(once, cleanUpInstructions(once))
        assertEquals(listOf(20.0, 300.0), once.map { it.distanceFromStartMeters })
    }

    private fun ins(type: InstructionType, distance: Double, angle: Double, reason: String) = RouteInstruction(
        type = type,
        nodeId = distance.toLong(),
        routeIndex = distance.toInt(),
        distanceFromStartMeters = distance,
        turnAngleDegrees = angle,
        junctionBranches = 2,
        incomingRoadType = "trunk",
        outgoingRoadType = "trunk",
        reason = reason,
    )
}
