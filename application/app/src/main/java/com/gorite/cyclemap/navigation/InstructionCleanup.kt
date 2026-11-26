package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.contract.TurnType
import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.TurnClassifier
import com.gorite.cyclemap.ui.map.toGuidanceTurnType

/**
 * 1 つの交差点の中で細切れになった案内を整える。紙屋町 (広島) のような、上下線が分かれた広い交差点で起きる:
 * - 交差点の入口で道路が切り替わるだけの「合流」が、すぐ先の本当の右折より先に主表示・音声を取ってしまう
 * - 右折の曲線が複数のノードに分かれ、選ぶ余地の無いノードごとに「右折」が並ぶ
 */
internal const val TURN_FRAGMENT_WINDOW_METERS = 30.0
internal const val MINOR_BEFORE_TURN_WINDOW_METERS = 30.0

/** 直後に曲がり角があれば、その案内に含めてよい (選ぶ余地を伝えない) 案内。脇道・分岐は選択なので含めない。 */
private val ABSORBABLE_MINOR_TYPES = setOf(
    InstructionType.STRAIGHT, InstructionType.MERGE,
    InstructionType.LANE_INCREASE, InstructionType.LANE_DECREASE,
)

internal fun cleanUpInstructions(instructions: List<RouteInstruction>): List<RouteInstruction> =
    dropMinorBeforeTurn(collapseTurnFragments(instructions))

/**
 * 曲がり角の直後 ([TURN_FRAGMENT_WINDOW_METERS] 以内) に、同じ向きで、選ぶ余地の無いノードでの曲がり角が続けば、
 * 同じ曲がり角の一部とみなして除く。
 */
internal fun collapseTurnFragments(instructions: List<RouteInstruction>): List<RouteInstruction> {
    var lastTurn: RouteInstruction? = null
    val out = ArrayList<RouteInstruction>(instructions.size)
    for (ins in instructions) {
        if (ins.isMinorForDisplay()) {
            out += ins
            continue
        }
        val prev = lastTurn
        val isFragment = prev != null &&
            ins.reason == TurnClassifier.REASON_SINGLE_BRANCH &&
            ins.distanceFromStartMeters - prev.distanceFromStartMeters <= TURN_FRAGMENT_WINDOW_METERS &&
            sideOf(ins) != 0 && sideOf(ins) == sideOf(prev)
        if (!isFragment) {
            out += ins
            lastTurn = ins
        }
    }
    return out
}

/** 画面の矢印の向き (-1 = 左、+1 = 右、0 = どちらでもない)。生の角度の符号は種類と食い違うことがあるので使わない。 */
private fun sideOf(ins: RouteInstruction): Int = when (ins.toGuidanceTurnType()) {
    TurnType.SlightLeft, TurnType.Left, TurnType.SharpLeft -> -1
    TurnType.SlightRight, TurnType.Right, TurnType.SharpRight -> 1
    else -> 0
}

/** 合流・車線の増減・直進のすぐ後 ([MINOR_BEFORE_TURN_WINDOW_METERS] 以内) に曲がり角があれば、その前の案内を除く。 */
internal fun dropMinorBeforeTurn(instructions: List<RouteInstruction>): List<RouteInstruction> {
    // 後ろから見て、各案内より先にある最初の曲がり角の距離を持つ
    val keep = BooleanArray(instructions.size) { true }
    var nextTurnDistance = Double.POSITIVE_INFINITY
    for (i in instructions.indices.reversed()) {
        val ins = instructions[i]
        if (!ins.isMinorForDisplay()) {
            nextTurnDistance = ins.distanceFromStartMeters
        } else if (ins.type in ABSORBABLE_MINOR_TYPES &&
            nextTurnDistance - ins.distanceFromStartMeters <= MINOR_BEFORE_TURN_WINDOW_METERS
        ) {
            keep[i] = false
        }
    }
    return instructions.filterIndexed { i, _ -> keep[i] }
}
