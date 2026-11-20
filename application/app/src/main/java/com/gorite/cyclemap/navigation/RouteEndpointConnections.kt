package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.MappedRouteResult
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
    val headLength = displayedRouteDistance(headSeed + first)
    val head = if (headLength in 3.0..200.0) headSeed else emptyList()
    val tail = if (displayedRouteDistance(listOf(last) + tailSeed) in 3.0..200.0) tailSeed else emptyList()
    return RouteDisplayGeometry(head + result.coordinates + tail, head.size, if (head.isEmpty()) 0.0 else headLength)
}
