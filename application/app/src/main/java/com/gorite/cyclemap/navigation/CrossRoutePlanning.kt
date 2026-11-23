package com.gorite.cyclemap.navigation

/** Android/osmdroid に依存しない座標。 */
internal data class CrossGeoPoint(val latitude: Double, val longitude: Double)

internal enum class CrossLegKind { SAME, CROSS, UNKNOWN }

internal fun classifyLeg(startPref: String?, goalPref: String?): CrossLegKind = when {
    startPref == null || goalPref == null -> CrossLegKind.UNKNOWN
    startPref == goalPref -> CrossLegKind.SAME
    else -> CrossLegKind.CROSS
}

private const val MAX_PREFECTURE_CHAINS = 3

private data class PrefectureChainCandidate(val path: List<String>, val distanceMeters: Double)

/** 最少県数のチェーンを中心間距離順で最大3本返す。 */
internal fun planPrefectureChains(
    startPref: String,
    goalPref: String,
    available: Set<String>,
    centers: Map<String, CrossGeoPoint>,
): List<List<String>> {
    if (startPref !in available || goalPref !in available) return emptyList()
    if (startPref == goalPref) return listOf(listOf(startPref))

    // 目的県から幅優先探索し、各県からの最少の残り区間数を求める。
    val remainingHops = mutableMapOf(goalPref to 0)
    val queue = ArrayDeque<String>()
    queue.addLast(goalPref)
    while (queue.isNotEmpty() && startPref !in remainingHops) {
        val prefecture = queue.removeFirst()
        val nextHops = remainingHops.getValue(prefecture) + 1
        for (neighbor in PREFECTURE_ADJACENCY[prefecture].orEmpty()) {
            if (neighbor in available && neighbor !in remainingHops) {
                remainingHops[neighbor] = nextHops
                queue.addLast(neighbor)
            }
        }
    }
    val hopCount = remainingHops[startPref] ?: return emptyList()

    var candidates = mapOf(startPref to listOf(PrefectureChainCandidate(listOf(startPref), 0.0)))
    for (hops in hopCount downTo 1) {
        val next = mutableMapOf<String, MutableList<PrefectureChainCandidate>>()
        for ((prefecture, paths) in candidates) {
            for (neighbor in PREFECTURE_ADJACENCY[prefecture].orEmpty()) {
                // 残り区間が1つ減る隣県だけをたどると、最少県数の候補に絞れる。
                if (remainingHops[neighbor] != hops - 1) continue
                val from = centers[prefecture]
                val to = centers[neighbor]
                val distance = if (from == null || to == null) Double.POSITIVE_INFINITY else crossDistance(from, to)
                val arrivals = next.getOrPut(neighbor) { mutableListOf() }
                for (candidate in paths) {
                    arrivals.add(PrefectureChainCandidate(candidate.path + neighbor, candidate.distanceMeters + distance))
                }
            }
        }
        // 同じ県からの残りの区間は共通なので、短い上位3本で十分。
        candidates = next.mapValues { (_, paths) ->
            paths.sortedBy { it.distanceMeters }.take(MAX_PREFECTURE_CHAINS)
        }
    }
    return candidates[goalPref].orEmpty().map { it.path }
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
