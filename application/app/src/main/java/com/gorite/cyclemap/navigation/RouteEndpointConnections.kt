package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.MappedRouteResult
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.TrimmedRoute

internal data class RouteDisplayGeometry(
    val points: List<Pair<Double, Double>>,
    val nodePointOffset: Int,
    val headLengthMeters: Double,
)

/** 主経路・代替経路で同じ上限を使い、利用者の起点・終点まで表示線を接続する。 */
internal fun connectRouteEndpoints(
    result: MappedRouteResult,
    trim: TrimmedRoute?,
    stops: List<Pair<Double, Double>>,
    enabled: Boolean = true,
): RouteDisplayGeometry {
    val first = result.coordinates.firstOrNull()
    val last = result.coordinates.lastOrNull()
    if (!enabled || !result.isReachable || first == null || last == null || stops.size < 2) {
        return RouteDisplayGeometry(result.coordinates, 0, 0.0)
    }
    val headSeed = trim?.head?.takeIf { it.isNotEmpty() } ?: listOf(stops.first())
    val tailSeed = trim?.tail?.takeIf { it.isNotEmpty() } ?: listOf(stops.last())
    val head = keepEndpointRoadSegment(headSeed, first)
    val tail = keepEndpointRoadSegment(tailSeed.asReversed(), last).asReversed()
    val headLength = displayedRouteDistance(head + first)
    return RouteDisplayGeometry(head + result.coordinates + tail, head.size, headLength)
}

/** 停止地点から内側に並ぶ点のうち、道路外の接続線だけを距離で判定する。 */
private fun keepEndpointRoadSegment(
    pointsFromStop: List<Pair<Double, Double>>,
    routeNode: Pair<Double, Double>,
): List<Pair<Double, Double>> {
    val roadPoint = pointsFromStop.getOrElse(1) { routeNode }
    val connectionLength = displayedRouteDistance(listOf(pointsFromStop.first(), roadPoint))
    return if (connectionLength in 3.0..200.0) pointsFromStop else pointsFromStop.drop(1)
}

/** 表示線に付け足した先頭区間の分だけ、各案内の起点からの距離を後ろへずらす。 */
internal fun shiftInstructionsForHead(
    instructions: List<RouteInstruction>,
    headLengthMeters: Double,
): List<RouteInstruction> {
    if (headLengthMeters <= 0.0) return instructions
    return instructions.map { it.copy(distanceFromStartMeters = it.distanceFromStartMeters + headLengthMeters) }
}
