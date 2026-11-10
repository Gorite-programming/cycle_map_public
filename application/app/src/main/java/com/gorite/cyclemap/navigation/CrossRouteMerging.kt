package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RouteSegmentGrade

/** A/B グラフから取得した、統合経路用の勾配・標高。 */
internal data class CrossRouteElevationData(
    val segments: List<RouteSegmentGrade>,
    val nodeElevations: List<Double?>,
)

internal data class CrossInstructionPart(
    val instructions: List<RouteInstruction>,
    val nodeCount: Int,
    val distanceMeters: Double,
)

internal data class CrossElevationPart(
    val segments: List<RouteSegmentGrade>,
    val nodeElevations: List<Double?>,
)

/** A/B の案内を統合経路の routeIndex と距離に変換する。境界ポータルのB側案内は重複させない。 */
internal fun mergeInstructionParts(
    aInstructions: List<RouteInstruction>,
    bInstructions: List<RouteInstruction>,
    aNodeCount: Int,
    aDistanceMeters: Double,
): List<RouteInstruction> {
    return mergeInstructionParts(listOf(
        CrossInstructionPart(aInstructions, aNodeCount, aDistanceMeters),
        CrossInstructionPart(bInstructions, Int.MAX_VALUE, 0.0),
    ))
}

/** 任意数の連続グラフ部品を全体 routeIndex / 累積距離へ変換する。 */
internal fun mergeInstructionParts(parts: List<CrossInstructionPart>): List<RouteInstruction> {
    val out = ArrayList<RouteInstruction>()
    var nodeOffset = 0
    var distanceOffset = 0.0
    parts.forEachIndexed { i, part ->
        if (part.nodeCount <= 0) return@forEachIndexed
        val skipBoundary = if (i == 0) 0 else 1
        part.instructions.asSequence()
            .filter { it.routeIndex >= skipBoundary && it.routeIndex < part.nodeCount }
            .mapTo(out) { it.copy(
                routeIndex = it.routeIndex + nodeOffset - skipBoundary,
                distanceFromStartMeters = it.distanceFromStartMeters + distanceOffset,
            ) }
        nodeOffset += (part.nodeCount - skipBoundary).coerceAtLeast(0)
        distanceOffset += part.distanceMeters
    }
    return out.sortedWith(compareBy<RouteInstruction> { it.routeIndex }.thenBy { it.distanceFromStartMeters })
}

/** 同一ポータルを1ノードとしてA/Bの勾配・標高列を連結する。 */
internal fun mergeElevationParts(
    aSegments: List<RouteSegmentGrade>,
    aElevations: List<Double?>,
    bSegments: List<RouteSegmentGrade>,
    bElevations: List<Double?>,
): CrossRouteElevationData {
    return mergeElevationParts(listOf(CrossElevationPart(aSegments, aElevations), CrossElevationPart(bSegments, bElevations)))
}

/** 任意数の勾配・標高部品を境界ノードを重複させず連結する。 */
internal fun mergeElevationParts(parts: List<CrossElevationPart>): CrossRouteElevationData {
    val segments = ArrayList<RouteSegmentGrade>()
    val elevations = ArrayList<Double?>()
    parts.forEach { part ->
        require(part.nodeElevations.isEmpty() || part.segments.size == part.nodeElevations.size - 1)
        segments.addAll(part.segments)
        if (part.nodeElevations.isNotEmpty()) {
            elevations.addAll(part.nodeElevations.drop(if (elevations.isEmpty()) 0 else 1))
        }
    }
    require(elevations.isEmpty() || segments.size == (elevations.size - 1).coerceAtLeast(0))
    return CrossRouteElevationData(segments, elevations)
}
