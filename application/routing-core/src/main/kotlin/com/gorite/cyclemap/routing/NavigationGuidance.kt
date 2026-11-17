package com.gorite.cyclemap.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

enum class ManeuverType {
    RIGHT,
    LEFT,
    U_TURN,
}

data class Maneuver(
    val type: ManeuverType,
    val distanceFromStartMeters: Double,
)

data class RouteProgress(
    val distanceFromStartMeters: Double,
    val routeDistanceMeters: Double,
    val distanceToRouteMeters: Double,
    val nextManeuver: Maneuver?,
    val distanceToNextManeuverMeters: Double?,
)

fun extractManeuvers(
    points: List<RoutePoint>,
    turnThresholdDegrees: Double = 20.0,
    uTurnThresholdDegrees: Double = 150.0,
): List<Maneuver> {
    if (points.size < 3) return emptyList()

    val cumulative = cumulativeDistances(points)
    return buildList {
        for (index in 1 until points.lastIndex) {
            val incoming = bearingBetween(points[index - 1], points[index]) ?: continue
            val outgoing = bearingBetween(points[index], points[index + 1]) ?: continue
            val change = normalizeBearingChange(outgoing - incoming)
            val magnitude = abs(change)
            if (magnitude < turnThresholdDegrees) continue

            val type = when {
                magnitude >= uTurnThresholdDegrees -> ManeuverType.U_TURN
                change > 0.0 -> ManeuverType.RIGHT
                else -> ManeuverType.LEFT
            }
            add(Maneuver(type, cumulative[index]))
        }
    }
}

/** 経路確定時に累積距離を保存し、測位ごとの再計算を避ける。 */
class RouteProgressIndex(route: List<RoutePoint>) {
    val points: List<RoutePoint> = route.toList()
    private val cumulative = cumulativeDistances(points)
    val distanceMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    fun calculate(
        location: RoutePoint,
        maneuvers: List<Maneuver> = emptyList(),
        previousProgressMeters: Double? = null,
        possibleTravelMeters: Double = 0.0,
        bearingDegrees: Double? = null,
    ): RouteProgress? {
        if (points.isEmpty()) return null
        if (points.size == 1) return RouteProgress(0.0, 0.0, haversineMeters(location, points[0]), maneuvers.firstOrNull(), null)
        var bestDistance = Double.POSITIVE_INFINITY
        var bestScore = Double.POSITIVE_INFINITY
        var bestProgress = 0.0
        fun search(first: Int, last: Int, low: Double = 0.0, high: Double = distanceMeters) {
            for (index in first..last) {
                val length = cumulative[index + 1] - cumulative[index]
                val projection = projectOntoSegment(location, points[index], points[index + 1])
                val projectedProgress = (cumulative[index] + length * projection.fraction).coerceIn(low, high.coerceAtLeast(low))
                val fraction = if (length > 0.0) ((projectedProgress - cumulative[index]) / length).coerceIn(0.0, 1.0) else 0.0
                val distance = if (fraction == projection.fraction) projection.distanceMeters else haversineMeters(location,
                    RoutePoint(points[index].latitude + (points[index + 1].latitude - points[index].latitude) * fraction,
                        points[index].longitude + (points[index + 1].longitude - points[index].longitude) * fraction))
                val bearing = bearingBetween(points[index], points[index + 1])
                val penalty = if (bearingDegrees?.isFinite() == true && bearing != null) {
                    abs(normalizeBearingChange(bearing - bearingDegrees)) / 180.0 * HEADING_PENALTY_METERS
                } else 0.0
                val score = distance + penalty
                if (score < bestScore) {
                    bestScore = score
                    bestDistance = distance
                    bestProgress = cumulative[index] + length * fraction
                }
            }
        }
        if (previousProgressMeters?.isFinite() == true) {
            val low = (previousProgressMeters - BACKWARD_WINDOW_METERS).coerceIn(0.0, distanceMeters)
            val high = previousProgressMeters + possibleTravelMeters.coerceAtLeast(0.0) + FORWARD_WINDOW_METERS
            val first = (cumulative.binarySearch(low).let { if (it >= 0) it else -it - 2 }).coerceIn(0, points.lastIndex - 1)
            val last = (cumulative.binarySearch(high).let { if (it >= 0) it else -it - 2 }).coerceIn(first, points.lastIndex - 1)
            search(first, last, low, high.coerceAtMost(distanceMeters))
        }
        if (bestDistance > REACQUIRE_DISTANCE_METERS) {
            bestScore = Double.POSITIVE_INFINITY
            search(0, points.lastIndex - 1)
        }
        val progress = bestProgress.coerceIn(0.0, distanceMeters)
        var left = 0
        var right = maneuvers.size
        while (left < right) {
            val middle = (left + right) ushr 1
            if (maneuvers[middle].distanceFromStartMeters <= progress) left = middle + 1 else right = middle
        }
        val next = maneuvers.getOrNull(left)
        return RouteProgress(progress, distanceMeters, bestDistance, next,
            next?.let { (it.distanceFromStartMeters - progress).coerceAtLeast(0.0) })
    }

    fun pointAt(distanceFromStartMeters: Double): Pair<RoutePoint, Double?>? {
        if (points.size < 2) return null
        val target = distanceFromStartMeters.coerceIn(0.0, distanceMeters)
        // 境界では従来と同じく直前の区間の方位を返す。
        var left = 1
        var right = cumulative.lastIndex
        while (left < right) {
            val middle = (left + right) ushr 1
            if (cumulative[middle] < target) left = middle + 1 else right = middle
        }
        val i = left - 1
        val length = cumulative[i + 1] - cumulative[i]
        val fraction = if (length > 0.0) (target - cumulative[i]) / length else 0.0
        return RoutePoint(points[i].latitude + (points[i + 1].latitude - points[i].latitude) * fraction,
            points[i].longitude + (points[i + 1].longitude - points[i].longitude) * fraction) to bearingBetween(points[i], points[i + 1])
    }

    companion object {
        // GPS の揺れを許容し、交差点で遠い区間へ飛ぶのを防ぐ。
        private const val BACKWARD_WINDOW_METERS = 50.0
        private const val FORWARD_WINDOW_METERS = 100.0
        // 近傍から大きく外れたときだけ全経路で再捕捉する。
        private const val REACQUIRE_DISTANCE_METERS = 100.0
        // 重なる往復路では進行方向が合う区間を優先する。
        private const val HEADING_PENALTY_METERS = 30.0
    }
}

fun calculateRouteProgress(
    location: RoutePoint,
    route: List<RoutePoint>,
    maneuvers: List<Maneuver> = extractManeuvers(route),
): RouteProgress? = RouteProgressIndex(route).calculate(location, maneuvers)

private data class Projection(val fraction: Double, val distanceMeters: Double)

private fun projectOntoSegment(point: RoutePoint, start: RoutePoint, end: RoutePoint): Projection {
    val referenceLatitude = Math.toRadians(point.latitude)
    val metersPerLatitude = 6_371_000.0 * Math.PI / 180.0
    val metersPerLongitude = metersPerLatitude * cos(referenceLatitude)
    fun x(p: RoutePoint) = (p.longitude - point.longitude) * metersPerLongitude
    fun y(p: RoutePoint) = (p.latitude - point.latitude) * metersPerLatitude

    val startX = x(start)
    val startY = y(start)
    val deltaX = x(end) - startX
    val deltaY = y(end) - startY
    val lengthSquared = deltaX * deltaX + deltaY * deltaY
    val fraction = if (lengthSquared == 0.0) 0.0 else {
        ((-startX * deltaX) + (-startY * deltaY)) / lengthSquared
    }.coerceIn(0.0, 1.0)
    val projected = RoutePoint(
        latitude = start.latitude + (end.latitude - start.latitude) * fraction,
        longitude = start.longitude + (end.longitude - start.longitude) * fraction,
    )
    return Projection(fraction, haversineMeters(point, projected))
}

private fun cumulativeDistances(points: List<RoutePoint>): List<Double> {
    val result = ArrayList<Double>(points.size)
    result += 0.0
    for (index in 1 until points.size) {
        result += result.last() + haversineMeters(points[index - 1], points[index])
    }
    return result
}

private fun bearingBetween(from: RoutePoint, to: RoutePoint): Double? {
    val latitude1 = Math.toRadians(from.latitude)
    val latitude2 = Math.toRadians(to.latitude)
    val deltaLongitude = Math.toRadians(to.longitude - from.longitude)
    val y = sin(deltaLongitude) * cos(latitude2)
    val x = cos(latitude1) * sin(latitude2) - sin(latitude1) * cos(latitude2) * cos(deltaLongitude)
    if (x == 0.0 && y == 0.0) return null
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun normalizeBearingChange(change: Double): Double = ((change + 540.0) % 360.0) - 180.0

private fun haversineMeters(from: RoutePoint, to: RoutePoint): Double =
    haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
