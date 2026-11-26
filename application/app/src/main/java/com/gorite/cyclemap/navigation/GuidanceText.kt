package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.IconResolver
import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.RouteInstruction

private const val JUNCTION_NAME_MAX_LENGTH = 12

/** 名前を付けると不自然になる案内 (Uターン・ロータリー・ランプ・連続分岐)。名前があっても今の文言にする。 */
private val TYPES_WITHOUT_NAMED_GUIDANCE = setOf(
    InstructionType.U_TURN_LEFT, InstructionType.U_TURN_RIGHT,
    InstructionType.ROUNDABOUT, InstructionType.RAMP_ENTRY, InstructionType.RAMP_EXIT,
    InstructionType.CONSECUTIVE_FORK,
)

/**
 * 「十字路を右折」のように、交差点の形が先に付くラベルの案内。名前があるときは形の部分を名前に置き換える
 * (「本町一丁目 を右折」)。ふつうの十字路の曲がり角はほとんどがこれなので、除くと名前がほぼ出ない。
 */
private val SHAPE_PREFIXED_TYPES = setOf(
    InstructionType.FORK_BOTH, InstructionType.Y_JUNCTION, InstructionType.T_JUNCTION,
    InstructionType.CROSS_JUNCTION, InstructionType.MULTI_JUNCTION,
)

/** 交差点名の後ろに「を」で続ける動作 (「右折」「左方向」など)。名前を付けない案内は null。 */
internal fun namedActionLabel(instruction: RouteInstruction): String? {
    if (instruction.isMinorForDisplay() || instruction.type in TYPES_WITHOUT_NAMED_GUIDANCE) return null
    val label = IconResolver.getLabel(instruction.type, instruction.turnAngleDegrees)
    if (instruction.type !in SHAPE_PREFIXED_TYPES) return label
    // 「T字路」のように向きの無いラベルは置き換えられない
    return label.substringAfter("を", "").takeIf { it.isNotEmpty() }
}

internal fun maneuverGuidanceText(
    instruction: RouteInstruction,
    distanceMeters: Double,
    includeJunctionName: Boolean = true,
): String {
    val label = IconResolver.getLabel(instruction.type, instruction.turnAngleDegrees)
    val name = instruction.junctionName
    val action = if (includeJunctionName && name != null) namedActionLabel(instruction) else null
    if (name != null && action != null) {
        val shortName = if (name.codePointCount(0, name.length) > JUNCTION_NAME_MAX_LENGTH) {
            name.substring(0, name.offsetByCodePoints(0, JUNCTION_NAME_MAX_LENGTH - 1)) + "…"
        } else {
            name
        }
        val prefix = if (distanceMeters >= 500.0) "この先 " else ""
        return "$prefix$shortName を$action"
    }
    return when {
        distanceMeters >= 500.0 -> "この先 ${label}です"
        distanceMeters >= 100.0 -> "${label}です"
        distanceMeters >= 30.0 -> "まもなく${label}です"
        else -> "${label}です"
    }
}

internal fun landscapeStreetLabel(junctionName: String?, roadName: String?, roadClassLabel: String?): String? =
    when {
        junctionName == null -> roadName ?: roadClassLabel
        roadName.isNullOrEmpty() -> junctionName
        else -> "$junctionName → $roadName"
    }

/** 一覧の太字の行。名前があれば「右折 本町一丁目」(十字路などは形の部分を除く)。 */
internal fun stepListLabel(step: UpcomingStep): String {
    val name = step.instruction.junctionName ?: return step.label
    return "${namedActionLabel(step.instruction) ?: step.label} $name"
}
