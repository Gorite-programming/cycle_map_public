package com.gorite.cyclemap.navigation

/** Android/osmdroid に依存しない座標。 */
internal data class CrossGeoPoint(val latitude: Double, val longitude: Double)

internal enum class CrossLegKind { SAME, CROSS, UNKNOWN }

internal fun classifyLeg(startPref: String?, goalPref: String?): CrossLegKind = when {
    startPref == null || goalPref == null -> CrossLegKind.UNKNOWN
    startPref == goalPref -> CrossLegKind.SAME
    else -> CrossLegKind.CROSS
}

private val PREFECTURE_NEIGHBORS = listOf(
    "山口県" to "島根県", "山口県" to "広島県", "島根県" to "広島県",
    "島根県" to "鳥取県", "広島県" to "岡山県", "広島県" to "鳥取県",
    "岡山県" to "鳥取県",
)

/** 最少県数のチェーンを中心間距離順で最大3本返す。 */
internal fun planPrefectureChains(
    startPref: String,
    goalPref: String,
    available: Set<String>,
    centers: Map<String, CrossGeoPoint>,
): List<List<String>> {
    if (startPref !in available || goalPref !in available) return emptyList()
    if (startPref == goalPref) return listOf(listOf(startPref))
    val adjacency = HashMap<String, MutableList<String>>()
    PREFECTURE_NEIGHBORS.forEach { (a, b) ->
        if (a in available && b in available) {
            adjacency.getOrPut(a) { mutableListOf() }.add(b)
            adjacency.getOrPut(b) { mutableListOf() }.add(a)
        }
    }
    var paths = listOf(listOf(startPref))
    while (paths.isNotEmpty()) {
        val next = paths.flatMap { path ->
            (adjacency[path.last()].orEmpty() - path.toSet()).map { path + it }
        }
        val hits = next.filter { it.last() == goalPref }
        if (hits.isNotEmpty()) return hits.sortedBy { path ->
            path.zipWithNext().sumOf { (a, b) ->
                val ca = centers[a]; val cb = centers[b]
                if (ca == null || cb == null) Double.POSITIVE_INFINITY else crossDistance(ca, cb)
            }
        }.take(3)
        paths = next.filter { it.last() != goalPref }
    }
    return emptyList()
}

private fun crossDistance(a: CrossGeoPoint, b: CrossGeoPoint): Double {
    val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
    val dp = p2 - p1; val dl = Math.toRadians(b.longitude - a.longitude)
    val h = kotlin.math.sin(dp / 2).let { it * it } + kotlin.math.cos(p1) * kotlin.math.cos(p2) *
        kotlin.math.sin(dl / 2).let { it * it }
    return 12_742_000.0 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

internal data class CrossRoutePathPart(
    val nodeIds: List<Long>,
    val coordinates: List<Pair<Double, Double>>,
    val distanceMeters: Double,
    val cost: Double,
    val nodeIndices: List<Int> = emptyList(),
)

internal data class MergedCrossRoutePath(
    val nodeIds: List<Long>,
    val coordinates: List<Pair<Double, Double>>,
    val distanceMeters: Double,
    val cost: Double,
)

/** 隣接部品間の重複ノードを除き、統合経路を作る。 */
internal fun mergeRoutePathParts(parts: List<CrossRoutePathPart>): MergedCrossRoutePath {
    val ids = ArrayList<Long>(); val coords = ArrayList<Pair<Double, Double>>()
    var distance = 0.0; var cost = 0.0
    for (part in parts) {
        val duplicate = ids.isNotEmpty() && part.nodeIds.isNotEmpty() &&
            (ids.last() == part.nodeIds.first() || coordinatesNearlyEqual(coords.last(), part.coordinates.first()))
        ids.addAll(part.nodeIds.drop(if (duplicate) 1 else 0))
        coords.addAll(part.coordinates.drop(if (duplicate) 1 else 0))
        distance += part.distanceMeters; cost += part.cost
    }
    return MergedCrossRoutePath(ids, coords, distance, cost)
}

private fun coordinatesNearlyEqual(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean =
    crossDistance(CrossGeoPoint(a.first, a.second), CrossGeoPoint(b.first, b.second)) <= 2.0
