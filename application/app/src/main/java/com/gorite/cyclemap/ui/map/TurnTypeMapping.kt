package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.contract.TurnType
import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.RouteInstruction
import kotlin.math.abs

/**
 * 案内の種別 ([RouteInstruction]) を、ヘッダーの矢印アイコン用 [TurnType] に変換する。
 *
 * T字路・十字路・Y字路などの交差点系は種別そのものに左右がないため、
 * 符号付き転回角 ([RouteInstruction.turnAngleDegrees], +が右) から向きを決める。
 * これをしないと「T字路を左折」の案内で矢印だけが直進になる。
 */
internal fun RouteInstruction?.toGuidanceTurnType(): TurnType {
    if (this == null) return TurnType.Straight
    return when (type) {
        InstructionType.LEFT_TURN -> TurnType.Left
        InstructionType.RIGHT_TURN -> TurnType.Right
        InstructionType.SHARP_LEFT -> TurnType.SharpLeft
        InstructionType.SHARP_RIGHT -> TurnType.SharpRight
        InstructionType.FORK_LEFT,
        InstructionType.DIAGONAL_FORK_LEFT,
        InstructionType.FORK_UP_LEFT,
        InstructionType.FORK_DOWN_LEFT -> TurnType.SlightLeft
        InstructionType.FORK_RIGHT,
        InstructionType.DIAGONAL_FORK_RIGHT,
        InstructionType.FORK_UP_RIGHT,
        InstructionType.FORK_DOWN_RIGHT -> TurnType.SlightRight
        InstructionType.ROUNDABOUT -> TurnType.Roundabout
        InstructionType.U_TURN_LEFT,
        InstructionType.U_TURN_RIGHT -> TurnType.Uturn
        InstructionType.STRAIGHT -> TurnType.Straight
        // T/Y/十字/多叉路・合流・側道など: 種別に向きがないので転回角で決める
        else -> turnTypeFromAngle(turnAngleDegrees)
    }
}

/** 符号付き転回角 (度, +が右) から [TurnType] を決める。 */
internal fun turnTypeFromAngle(angleDegrees: Double): TurnType {
    val magnitude = abs(angleDegrees)
    val right = angleDegrees > 0.0
    return when {
        magnitude < 15.0 -> TurnType.Straight
        magnitude < 45.0 -> if (right) TurnType.SlightRight else TurnType.SlightLeft
        magnitude < 135.0 -> if (right) TurnType.Right else TurnType.Left
        else -> if (right) TurnType.SharpRight else TurnType.SharpLeft
    }
}

/** 道路種別 (OSM highway) を、案内バーに添える日本語の道路区分にする。 */
internal fun roadClassLabel(highway: String?): String? = when (highway) {
    "primary", "primary_link", "trunk", "trunk_link" -> "幹線道路"
    "secondary", "secondary_link" -> "主要な道"
    "tertiary", "tertiary_link", "unclassified" -> "一般道"
    "residential", "living_street" -> "住宅街の道"
    "service" -> "構内・私道"
    "track" -> "農道・林道"
    "path" -> "小道"
    "cycleway" -> "自転車道"
    else -> null
}
