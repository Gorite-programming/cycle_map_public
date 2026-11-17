package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.routing.RouteProgressIndex
import com.gorite.cyclemap.routing.haversineMeters

/** 通過済みの接頭部分だけを取り除き、未通過の経由地の順序を保つ。 */
internal class WaypointPassTracker(index: RouteProgressIndex, private val waypoints: List<RoutePoint>) {
    private val distances = buildList {
        var previous: Double? = null
        for (point in waypoints) {
            val distance = index.calculate(point, previousProgressMeters = previous,
                possibleTravelMeters = index.distanceMeters)?.distanceFromStartMeters ?: Double.POSITIVE_INFINITY
            add(distance)
            previous = distance
        }
    }
    private var passed = 0

    fun update(location: RoutePoint, accuracyMeters: Double, progress: RouteProgress?): Int {
        if (!accuracyMeters.isFinite() || accuracyMeters !in 0.0..MAX_ACCURACY_METERS) return passed
        while (passed < waypoints.size) {
            val waypoint = waypoints[passed]
            val near = haversineMeters(location.latitude, location.longitude, waypoint.latitude, waypoint.longitude) <= PASS_RADIUS_METERS
            val beyond = progress != null && progress.distanceToRouteMeters <= MAX_ACCURACY_METERS &&
                progress.distanceFromStartMeters >= distances[passed] + PASS_RADIUS_METERS
            if (!near && !beyond) break
            passed++
        }
        return passed
    }

    companion object {
        // 経由地の目前で GPS が少し揺れても通過できる距離にする。
        private const val PASS_RADIUS_METERS = 40.0
        // 推測位置や精度の悪い測位で経由地を消さない。
        private const val MAX_ACCURACY_METERS = 50.0
    }
}
