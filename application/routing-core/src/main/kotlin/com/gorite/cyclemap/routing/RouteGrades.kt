package com.gorite.cyclemap.routing

/** 経路の 1 区間 (隣り合う 2 点) の距離と勾配。勾配が不明 (グラフに無い・点がグラフの外) なら null。 */
data class RouteSegmentGrade(val distanceMeters: Double, val gradePercent: Double?)

/**
 * 経路の座標列 (経路探索の結果の座標、すなわちグラフのノードの座標) から、区間ごとの距離と勾配を取り出す。
 * 勾配は osm-importer が標高タイルから辺に書き込んだ値で、オフラインで使える。
 * 座標がグラフのノードから [maxSnapMeters] より離れている区間は、勾配不明 (null) とする。
 * 呼び出し側で、グラフを他のスレッドが入れ替えないよう読み取りロックを保持すること。
 */
fun LazyMappedRoadGraph.segmentGradesAlong(
    coordinates: List<Pair<Double, Double>>,
    maxSnapMeters: Double = 5.0,
): List<RouteSegmentGrade> {
    if (coordinates.size < 2) return emptyList()
    val indices = snappedIndicesAlong(coordinates, maxSnapMeters)
    return (0 until coordinates.size - 1).map { i ->
        val (lat1, lon1) = coordinates[i]
        val (lat2, lon2) = coordinates[i + 1]
        val straight = haversineMeters(lat1, lon1, lat2, lon2)
        val a = indices[i]
        val b = indices[i + 1]
        val branch = if (a >= 0 && b >= 0 && a != b) {
            outgoingBranches(a).filter { it.targetIndex == b }.minByOrNull { it.distanceMeters }
        } else null
        when {
            branch == null -> RouteSegmentGrade(straight, null)
            branch.gradePercent.isNaN() -> RouteSegmentGrade(branch.distanceMeters, null)
            else -> RouteSegmentGrade(branch.distanceMeters, branch.gradePercent.toDouble())
        }
    }
}

/**
 * 経路の座標列の各点について、グラフのノードの絶対標高 (m) を返す。標高区間が無い graph、
 * またはノードから [maxSnapMeters] より離れた点は null。
 */
fun LazyMappedRoadGraph.nodeElevationsAlong(
    coordinates: List<Pair<Double, Double>>,
    maxSnapMeters: Double = 5.0,
): List<Double?> {
    if (!hasElevations) return coordinates.map { null }
    val indices = snappedIndicesAlong(coordinates, maxSnapMeters)
    return indices.map { if (it >= 0) elevationMetersAt(it) else null }
}

/**
 * 経路の座標列の各点に対応するノードの番号 ([maxSnapMeters] より離れていれば -1)。
 * 経路は隣り合うノードをたどるので、直前の点のノードから出る辺の行き先に同じ座標があればそれを使い、
 * 無いときだけ最寄りのノードを探す (最寄り探索は 1 点 0.1ms 前後かかり、実機では経路 1 本で数秒になっていた)。
 */
internal fun LazyMappedRoadGraph.snappedIndicesAlong(coordinates: List<Pair<Double, Double>>, maxSnapMeters: Double): IntArray {
    val out = IntArray(coordinates.size)
    var previous = -1
    for (i in coordinates.indices) {
        val (lat, lon) = coordinates[i]
        val viaEdge = if (previous >= 0) neighborAt(previous, lat, lon) else -1
        val index = if (viaEdge >= 0) viaEdge else nearestNodeIndex(lat, lon)
        val node = nodeAt(index)
        out[i] = if (haversineMeters(lat, lon, node.latitude, node.longitude) <= maxSnapMeters) index else -1
        previous = index
    }
    return out
}

/**
 * [segmentGradesAlong] の、graph 内のノード番号の列を受け取る版 (探索結果の [MappedRouteResult.nodeIndices])。
 * 座標から最寄りのノードを探し直さないので速い (実機で 1 経路あたり数秒 → 数十ミリ秒)。結果は座標版と同じ。
 */
fun LazyMappedRoadGraph.segmentGradesAlongIndices(indices: List<Int>): List<RouteSegmentGrade> {
    if (indices.size < 2) return emptyList()
    return (0 until indices.size - 1).map { i ->
        val a = indices[i]
        val b = indices[i + 1]
        val edge = if (a != b) edgeDistanceAndGrade(a, b) else null
        when {
            edge == null -> {
                val na = nodeAt(a); val nb = nodeAt(b)
                RouteSegmentGrade(haversineMeters(na.latitude, na.longitude, nb.latitude, nb.longitude), null)
            }
            edge.second.isNaN() -> RouteSegmentGrade(edge.first, null)
            else -> RouteSegmentGrade(edge.first, edge.second.toDouble())
        }
    }
}

/** [nodeElevationsAlong] の、ノード番号の列を受け取る版。 */
fun LazyMappedRoadGraph.nodeElevationsAt(indices: List<Int>): List<Double?> =
    if (!hasElevations) indices.map { null } else indices.map { elevationMetersAt(it) }
