package com.gorite.cyclemap.routing

import kotlin.math.cos
import kotlin.math.hypot

/**
 * 起点・終点の「辺への射影」による補正。探索は最寄りの"ノード"に合わせるため、
 * 起点 (終点) が長い辺の途中にあると、辺の端のノードまで行って戻る形のルートになる。
 *
 * ルートの両端から [WINDOW_METERS] 以内の辺について、起点 (終点) をそれぞれの辺に射影し、
 * 端のノードより近い辺があれば、そこまでのノードを落として「射影点から」始まる (終わる) ように直す。
 * 近さが同じ (1 m 以内) なら、より多く落とせる (ルートの内側の) 辺を選ぶ。
 * ノード列は落とした分だけ短くなり、nodeIds / nodeIndices / coordinates は同じ長さのまま同期する
 * (案内の routeIndex が崩れない)。射影点と、利用者の位置・目的地までの線は表示用に [TrimmedRoute.head] / [TrimmedRoute.tail] で返す。
 */
data class TrimmedRoute(
    val result: MappedRouteResult,
    /** 表示線の頭に足す点 (起点 → 射影点)。補正しなかったときは空。 */
    val head: List<Pair<Double, Double>>,
    /** 表示線の末尾に足す点 (射影点 → 目的地)。補正しなかったときは空。 */
    val tail: List<Pair<Double, Double>>,
)

private const val WINDOW_METERS = 250.0
private const val TIE_METERS = 1.0

private data class EdgeProjection(val t: Double, val lat: Double, val lon: Double, val distanceMeters: Double)

/** 作業順 (端から内側へ) の座標列に対する切り方。 */
private class Cut(val dropNodes: Int, val dropMeters: Double, val point: Pair<Double, Double>?)

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
    return EdgeProjection(
        t,
        a.first + (b.first - a.first) * t,
        a.second + (b.second - a.second) * t,
        hypot(px - bx * t, py - by * t),
    )
}

private fun cutFromEnd(lat: Double, lon: Double, c: List<Pair<Double, Double>>, minTrimMeters: Double): Cut? {
    if (c.size < 3) return null
    val nodeDistance = haversineMeters(lat, lon, c[0].first, c[0].second)
    class Cand(val e: Int, val p: EdgeProjection, val cumulative: Double, val length: Double)
    val cands = ArrayList<Cand>()
    var cum = 0.0
    for (e in 0 until c.lastIndex) {
        if (cum > WINDOW_METERS) break
        val len = haversineMeters(c[e].first, c[e].second, c[e + 1].first, c[e + 1].second)
        cands += Cand(e, projectOntoSegment(lat, lon, c[e], c[e + 1]), cum, len)
        cum += len
    }
    val bestDistance = cands.minOfOrNull { it.p.distanceMeters } ?: return null
    if (bestDistance >= nodeDistance - TIE_METERS) return null
    val best = cands.last { it.p.distanceMeters <= bestDistance + TIE_METERS }
    val t = best.p.t
    val atStart = t <= 0.0
    val atEnd = t >= 1.0
    val dropNodes = if (atStart) best.e else best.e + 1
    if (dropNodes == 0 || dropNodes > c.size - 2) return null
    val trimOffsetMeters = best.cumulative + t * best.length
    if (trimOffsetMeters < minTrimMeters) return null
    // result.coordinates は c[dropNodes] から始まるため、実際に落とした辺の長さだけを総距離から引く
    // (射影点 cut.point 〜 c[dropNodes] の区間は呼び出し側が head / tail 経由で接続する)。
    val dropMeters = if (atStart) best.cumulative else best.cumulative + best.length
    val point = if (!atStart && !atEnd) best.p.lat to best.p.lon else null
    return Cut(dropNodes, dropMeters, point)
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

    cutFromEnd(startLat, startLon, coords, minTrimMeters)?.let { cut ->
        from = cut.dropNodes
        trimmedMeters += cut.dropMeters
        head = listOfNotNull(startLat to startLon, cut.point)
    }

    // 終点: 起点側で落とした残りを、終わりの側から数えて切る。
    val remaining = coords.subList(from, to + 1)
    cutFromEnd(goalLat, goalLon, remaining.asReversed(), minTrimMeters)?.let { cut ->
        to -= cut.dropNodes
        trimmedMeters += cut.dropMeters
        tail = listOfNotNull(cut.point, goalLat to goalLon)
    }

    if (from == 0 && to == ids.size - 1 && head.isEmpty() && tail.isEmpty()) {
        return TrimmedRoute(result, emptyList(), emptyList())
    }

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
