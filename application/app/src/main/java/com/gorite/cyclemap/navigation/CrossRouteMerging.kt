package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RouteSegmentGrade

/** A/B グラフから取得した、統合経路用の勾配・標高。 */
internal data class CrossRouteElevationData(
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
    if (aNodeCount <= 0) return emptyList()
    val boundaryOffset = aNodeCount - 1
    val fromA = aInstructions.filter { it.routeIndex in 0 until aNodeCount }
    val fromB = bInstructions.asSequence()
        .filter { it.routeIndex > 0 }
        .map { it.copy(routeIndex = it.routeIndex + boundaryOffset, distanceFromStartMeters = it.distanceFromStartMeters + aDistanceMeters) }
        .toList()
    return (fromA + fromB).sortedWith(compareBy<RouteInstruction> { it.routeIndex }.thenBy { it.distanceFromStartMeters })
}

/** 同一ポータルを1ノードとしてA/Bの勾配・標高列を連結する。 */
internal fun mergeElevationParts(
    aSegments: List<RouteSegmentGrade>,
    aElevations: List<Double?>,
    bSegments: List<RouteSegmentGrade>,
    bElevations: List<Double?>,
): CrossRouteElevationData {
    require(aElevations.isEmpty() || aSegments.size == aElevations.size - 1)
    require(bElevations.isEmpty() || bSegments.size == bElevations.size - 1)
    val segments = aSegments + bSegments
    val elevations = when {
        aElevations.isEmpty() -> bElevations
        bElevations.isEmpty() -> aElevations
        else -> aElevations + bElevations.drop(1)
    }
    require(segments.size == (elevations.size - 1).coerceAtLeast(0))
    return CrossRouteElevationData(segments, elevations)
}
