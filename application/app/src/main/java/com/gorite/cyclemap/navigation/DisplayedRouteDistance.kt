package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.haversineMeters

/** 表示線そのものを測り、端点の射影区間の二重加算を防ぐ。 */
internal fun displayedRouteDistance(points: List<Pair<Double, Double>>): Double =
    points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }
