package com.gorite.cyclemap.routing

/** [routeWithSnapFallback] の結果。実際に使った始点・終点のノード番号を含む。 */
data class SnapRouteOutcome(
    val result: MappedRouteResult,
    val startIndex: Int,
    val goalIndex: Int,
    /** 最寄りのノード以外を使って到達できたか。 */
    val usedFallbackSnap: Boolean,
    /** 試した (始点, 終点) の組の数。 */
    val attempts: Int,
)

/** 失敗した探索がこの数より多くのノードを展開していたら、始点側は大きな道路網につながっている (= 孤立しているのは終点側)。 */
private const val LARGE_COMPONENT_NODES = 50_000

/**
 * 最寄りのノード同士で経路が引けない (孤立した道に当たった) ときだけ、次に近い候補で引き直す。
 *
 * 公園・河川敷・建物内などでは、最寄りのノードが小さな孤立した道の一部で、探索がすぐ尽きて到達不能になることがある
 * (広島の平和公園周辺では、格子状に取った点の約 2 割が該当した)。
 * 通常 (最初の組で到達できる) の動作・速度は変えない。打ち切り・キャンセルのときは引き直さない。
 *
 * 引き直しの順序:
 * 1. 最初の失敗で、探索が小さな範囲で尽きた (始点が孤立) なら、始点の候補だけを順に替える。小さな成分での失敗は安い。
 * 2. 探索が大きな範囲を展開してから失敗した (終点が孤立) なら、終点の候補を替える。大きな探索は高いので少数に絞る。
 * 3. 1 で全滅したときも、終点側を少数だけ試す。
 */
fun LazyMappedRoadGraph.routeWithSnapFallback(
    startLat: Double,
    startLon: Double,
    goalLat: Double,
    goalLon: Double,
    isCancelled: () -> Boolean = { false },
    maxExpandedNodes: Int = Int.MAX_VALUE,
    preference: RoutePreference = RoutePreference.RECOMMENDED,
    maxStartCandidates: Int = 24,
    maxGoalCandidates: Int = 6,
): SnapRouteOutcome {
    val starts = nearestNodeCandidates(startLat, startLon, radiusMeters = 400.0, maxCount = maxStartCandidates)
    val goals = nearestNodeCandidates(goalLat, goalLon, radiusMeters = 400.0, maxCount = maxGoalCandidates)
    if (starts.isEmpty() || goals.isEmpty()) {
        return SnapRouteOutcome(
            MappedRouteResult(emptyList(), 0.0, 0.0, emptyList(), wasCancelled = false, wasTruncated = false),
            -1, -1, usedFallbackSnap = false, attempts = 0,
        )
    }
    var attempts = 0
    fun attempt(s: Int, g: Int): SnapRouteOutcome {
        attempts++
        val result = route(
            starts[s], goals[g],
            isCancelled = isCancelled,
            maxExpandedNodes = maxExpandedNodes,
            preference = preference,
        )
        return SnapRouteOutcome(result, starts[s], goals[g], usedFallbackSnap = s != 0 || g != 0, attempts = attempts)
    }
    fun done(o: SnapRouteOutcome) = o.result.isReachable || o.result.wasCancelled || o.result.wasTruncated

    var last = attempt(0, 0)
    if (done(last)) return last
    val goalIsolated = lastExpandedNodes >= LARGE_COMPONENT_NODES

    if (!goalIsolated) {
        for (s in 1 until starts.size) {
            last = attempt(s, 0)
            if (done(last)) return last
        }
    }
    for (g in 1 until goals.size) {
        last = attempt(0, g)
        if (done(last)) return last
    }
    return last
}

/** 県またぎの端点が、これだけのノードにつながっていれば「大きな道路網の一部」とみなす。 */
const val WELL_CONNECTED_NODES = 5_000

/**
 * 最寄りのノードから順に、[minComponentNodes] 以上のノードへ広がれる (= 孤立していない) 候補を選ぶ。
 *
 * 県またぎの探索は始点・終点のノード番号を先に決めて複数のグラフにまたがって解くため、単独グラフの
 * [routeWithSnapFallback] のように失敗してから引き直せない。公園・駅前広場などで最寄りが小さな孤立した道だと、
 * 探索が数ノードで尽きて県またぎ全体が失敗し、県内だけの経路にすり替わっていた (岡山駅付近→姫路で確認)。
 * 最寄りが孤立していないときは最寄りをそのまま返す (通常の動作・結果は変えない)。
 * 候補がすべて孤立しているときも最寄りを返す。
 */
fun LazyMappedRoadGraph.nearestWellConnectedNodeIndex(
    latitude: Double,
    longitude: Double,
    minComponentNodes: Int = WELL_CONNECTED_NODES,
    radiusMeters: Double = 400.0,
    maxCandidates: Int = 24,
): Int {
    val candidates = nearestNodeCandidates(latitude, longitude, radiusMeters, maxCandidates)
    if (candidates.isEmpty()) return -1
    for (candidate in candidates) {
        if (reachesAtLeast(candidate, minComponentNodes)) return candidate
    }
    return candidates[0]
}

/** [start] から自転車で通れる辺をたどって、[minNodes] 個以上のノードに届くか (幅優先、届いた時点で打ち切る)。 */
internal fun LazyMappedRoadGraph.reachesAtLeast(start: Int, minNodes: Int): Boolean {
    val visited = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    visited += start; queue.addLast(start)
    val cursor = newRoutingEdgeCursor()
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        cursor.reset(current)
        while (cursor.next()) {
            val target = cursor.target
            if (target < 0 || routingEdgeMultiplier(cursor.type, cursor.grade, RoutePreference.RECOMMENDED, RouteCostMode.BICYCLE).isNaN()) continue
            if (visited.add(target)) {
                if (visited.size >= minNodes) return true
                queue.addLast(target)
            }
        }
    }
    return visited.size >= minNodes
}
