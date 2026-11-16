package com.gorite.cyclemap.routing

import kotlin.math.cos
import kotlin.math.hypot

/**
 * 起点・終点の「辺への射影」による補正。探索は最寄りの"ノード"に合わせるため、
 * 起点が長い辺の途中にあると、辺の端のノードまで行って戻る形のルートになる。
 *
 * 最初 (最後) の辺に起点 (終点) を射影した点が辺の内側に落ち、かつ端のノードより近いときは、
 * そのノードを落として「射影点から」始まる (終わる) ように直す。ノード列は落とした分だけ短くなり
 * nodeIds / nodeIndices / coordinates は同じ長さのまま同期する (案内の routeIndex が崩れない)。
 * 射影点と、利用者の位置・目的地までの線は表示用に [head] / [tail] で別に返す。
 */
data class TrimmedRoute(
    val result: MappedRouteResult,
    /** 表示線の頭に足す点 (起点 → 射影点)。補正しなかったときは空。 */
    val head: List<Pair<Double, Double>>,
    /** 表示線の末尾に足す点 (射影点 → 目的地)。補正しなかったときは空。 */
    val tail: List<Pair<Double, Double>>,
)

private data class EdgeProjection(val t: Double, val lat: Double, val lon: Double, val distanceMeters: Double)

/** 点 (lat, lon) を線分 a→b に射影する。短い距離なので局所の平面近似でよい。 */
private fun projectOntoSegment(lat: Double, lon: Double, a: Pair<Double, Double>, b: Pair<Double, Double>): EdgeProjection {
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(a.first))
    val bx = (b.second - a.second) * mPerDegLon
    val by = (b.first - a.first) * mPerDegLat
    val px = (lon - a.second) * mPerDegLon
    val py = (lat - a.first) * mPerDegLat
    val len2 = bx * bx + by * by
    val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val projLat = a.first + (b.first - a.first) * t
    val projLon = a.second + (b.second - a.second) * t
    val dist = hypot(px - bx * t, py - by * t)
    return EdgeProjection(t, projLat, projLon, dist)
}

fun trimRouteEnds(
    result: MappedRouteResult,
    startLat: Double,
    startLon: Double,
    goalLat: Double,
    goalLon: Double,
    /** これより短い補正は意味がないので行わない (ノードのすぐ脇にいるとき)。 */
    minTrimMeters: Double = 2.0,
): TrimmedRoute {
    val ids = result.nodeIds
    val coords = result.coordinates
    val idx = result.nodeIndices
    val hasIdx = idx.size == ids.size
    if (ids.size < 3 || coords.size != ids.size) return TrimmedRoute(result, emptyList(), emptyList())

    var from = 0
    var to = ids.size - 1
    var trimmedMeters = 0.0
    var head = emptyList<Pair<Double, Double>>()
    var tail = emptyList<Pair<Double, Double>>()

    // 起点: 最初の辺 (0→1) に射影する。
    run {
        val a = coords[0]
        val b = coords[1]
        val p = projectOntoSegment(startLat, startLon, a, b)
        val toNode = distanceMeters(startLat, startLon, a.first, a.second)
        val edgeLen = distanceMeters(a.first, a.second, b.first, b.second)
        val dropped = p.t * edgeLen
        if (p.t > 0.0 && p.t < 1.0 && p.distanceMeters < toNode && dropped >= minTrimMeters) {
            from = 1
            trimmedMeters += dropped
            head = listOf(startLat to startLon, p.lat to p.lon)
        }
    }

    // 終点: 最後の辺 (n-2→n-1) に射影する。起点側で落とした分を残してノードが 2 つ以上になるときだけ。
    run {
        if (to - from < 2) return@run
        val a = coords[to - 1]
        val b = coords[to]
        val p = projectOntoSegment(goalLat, goalLon, b, a) // b 側から見た位置 (t は終端 b からの割合)
        val toNode = distanceMeters(goalLat, goalLon, b.first, b.second)
        val edgeLen = distanceMeters(a.first, a.second, b.first, b.second)
        val dropped = p.t * edgeLen
        if (p.t > 0.0 && p.t < 1.0 && p.distanceMeters < toNode && dropped >= minTrimMeters) {
            to -= 1
            trimmedMeters += dropped
            tail = listOf(p.lat to p.lon, goalLat to goalLon)
        }
    }

    if (from == 0 && to == ids.size - 1) return TrimmedRoute(result, emptyList(), emptyList())

    val perMeterCost = if (result.totalDistanceMeters > 0.0) result.totalCost / result.totalDistanceMeters else 0.0
    val trimmed = result.copy(
        nodeIds = ids.subList(from, to + 1),
        coordinates = coords.subList(from, to + 1),
        nodeIndices = if (hasIdx) idx.subList(from, to + 1) else emptyList(),
        totalDistanceMeters = (result.totalDistanceMeters - trimmedMeters).coerceAtLeast(0.0),
        totalCost = (result.totalCost - trimmedMeters * perMeterCost).coerceAtLeast(0.0),
    )
    return TrimmedRoute(trimmed, head, tail)
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    haversineMeters(lat1, lon1, lat2, lon2)
