package com.gorite.cyclemap.routing

/** 共通 OSM ID のポータル。各配列は同じ位置が同じ ID を指す。 */
data class CrossPortals(val aIndices: IntArray, val bIndices: IntArray, val nodeIds: LongArray) {
    val size: Int get() = nodeIds.size
}

data class PortalCost(val nodeId: Long, val aIndex: Int, val cost: Double, val distanceMeters: Double)

data class PortalReach(
    val startIndex: Int,
    val portals: List<PortalCost>,
    /** A グラフのノード番号ごとの親。探索後に使い回される配列からコピー済み。 */
    val parentSnapshot: IntArray,
    val expandedNodes: Int,
    val wasCancelled: Boolean = false,
    val wasTruncated: Boolean = false,
)

data class PortalSource(
    val nodeId: Long,
    val aIndex: Int,
    val bIndex: Int,
    val initialCost: Double,
    val initialDistanceMeters: Double,
)

data class PortalSearchResult(
    val nodeIndices: List<Int>,
    val seedPortalId: Long?,
    val totalCost: Double,
    val totalDistanceMeters: Double,
    val expandedNodes: Int,
    val wasCancelled: Boolean = false,
    val wasTruncated: Boolean = false,
) {
    val isReachable: Boolean get() = nodeIndices.isNotEmpty() && !wasCancelled && !wasTruncated
}

data class CrossRoutePart(
    val nodeIds: List<Long> = emptyList(),
    val coordinates: List<Pair<Double, Double>> = emptyList(),
    val nodeIndices: List<Int> = emptyList(),
)

data class CrossRouteResult(
    val nodeIds: List<Long>,
    val coordinates: List<Pair<Double, Double>>,
    val totalDistanceMeters: Double,
    val totalCost: Double,
    val aPart: CrossRoutePart,
    val bPart: CrossRoutePart,
    val portalId: Long?,
    val wasCancelled: Boolean = false,
    val wasTruncated: Boolean = false,
) {
    val isReachable: Boolean get() = nodeIds.isNotEmpty() && !wasCancelled && !wasTruncated
}

/** ノードID昇順のプリミティブ配列同士をマージする。 */
fun portalsBetween(a: LazyMappedRoadGraph, b: LazyMappedRoadGraph): CrossPortals {
    val (aIds, aOrder) = a.sortedNodeIdIndex()
    val (bIds, bOrder) = b.sortedNodeIdIndex()
    val ai = IntArray(minOf(a.nodeCount, b.nodeCount))
    val bi = IntArray(ai.size)
    val ids = LongArray(ai.size)
    var i = 0; var j = 0; var n = 0
    while (i < aIds.size && j < bIds.size) {
        when {
            aIds[i] < bIds[j] -> i++
            aIds[i] > bIds[j] -> j++
            else -> { ai[n] = aOrder[i]; bi[n] = bOrder[j]; ids[n] = aIds[i]; n++; i++; j++ }
        }
    }
    return CrossPortals(ai.copyOf(n), bi.copyOf(n), ids.copyOf(n))
}

/** Dijkstra で到達可能な全ポータルを確定し、経路復元用の親配列をスナップショットする。 */
fun LazyMappedRoadGraph.routeToPortals(
    start: Int,
    portalIndices: IntArray,
    preference: RoutePreference = RoutePreference.RECOMMENDED,
    isCancelled: (() -> Boolean)? = null,
    maxExpandedNodes: Int = Int.MAX_VALUE,
    costMode: RouteCostMode = RouteCostMode.BICYCLE,
): PortalReach {
    require(start in 0 until nodeCount)
    require(maxExpandedNodes > 0)
    val pending = BooleanArray(nodeCount)
    var remaining = 0
    for (p in portalIndices) { require(p in 0 until nodeCount); if (!pending[p]) { pending[p] = true; remaining++ } }
    val cost = DoubleArray(nodeCount) { Double.POSITIVE_INFINITY }
    val distance = DoubleArray(nodeCount)
    val parent = IntArray(nodeCount) { -1 }
    val heap = PrimitiveHeap()
    val edges = newRoutingEdgeCursor()
    cost[start] = 0.0
    heap.push(start, 0.0, 0.0)
    val foundCosts = HashMap<Int, Pair<Double, Double>>()
    var expanded = 0
    var cancelled = false; var truncated = false
    while (!heap.isEmpty) {
        heap.pop(); val node = heap.node; val entry = heap.g
        if (entry != cost[node]) continue
        expanded++
        if (isCancelled?.invoke() == true) { cancelled = true; break }
        if (pending[node]) { pending[node] = false; remaining--; foundCosts[node] = cost[node] to distance[node] }
        if (remaining == 0) break
        if (expanded >= maxExpandedNodes) { truncated = true; break }
        edges.reset(node)
        while (edges.next()) {
            val target = edges.target
            if (target < 0) continue
            val multiplier = routingEdgeMultiplier(edges.type, edges.grade, preference, costMode)
            if (multiplier.isNaN()) continue
            val next = cost[node] + edges.distance * multiplier
            if (next < cost[target]) {
                cost[target] = next; distance[target] = distance[node] + edges.distance; parent[target] = node
                heap.push(target, next, next)
            }
        }
    }
    val idsByIndex = HashMap<Int, Long>(foundCosts.size)
    foundCosts.keys.forEach { idsByIndex[it] = nodeAt(it).id }
    val portals = foundCosts.map { (index, v) -> PortalCost(idsByIndex.getValue(index), index, v.first, v.second) }
    return PortalReach(start, portals, parent.copyOf(), expanded, cancelled, truncated)
}

/** 各 portal を初期 g 値付きの始点として扱う一回の A*。 */
fun LazyMappedRoadGraph.routeFromPortals(
    sources: List<PortalSource>,
    goal: Int,
    preference: RoutePreference = RoutePreference.RECOMMENDED,
    isCancelled: (() -> Boolean)? = null,
    maxExpandedNodes: Int = Int.MAX_VALUE,
    costMode: RouteCostMode = RouteCostMode.BICYCLE,
): PortalSearchResult {
    require(goal in 0 until nodeCount)
    require(maxExpandedNodes > 0)
    if (sources.isEmpty()) return PortalSearchResult(emptyList(), null, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0)
    val cost = DoubleArray(nodeCount) { Double.POSITIVE_INFINITY }
    val distance = DoubleArray(nodeCount)
    val parent = IntArray(nodeCount) { -1 }
    val origin = LongArray(nodeCount)
    val heap = PrimitiveHeap()
    val edges = newRoutingEdgeCursor()
    for (s in sources) {
        require(s.bIndex in 0 until nodeCount)
        if (s.initialCost < cost[s.bIndex]) {
            cost[s.bIndex] = s.initialCost; distance[s.bIndex] = s.initialDistanceMeters
            origin[s.bIndex] = s.nodeId
            heap.push(s.bIndex, s.initialCost, s.initialCost + straightLineHeuristic(s.bIndex, goal))
        }
    }
    var expanded = 0
    while (!heap.isEmpty) {
        heap.pop(); val node = heap.node; val entry = heap.g
        if (entry != cost[node]) continue
        expanded++
        if (isCancelled?.invoke() == true) return PortalSearchResult(emptyList(), null, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, expanded, wasCancelled = true)
        if (node == goal) {
            val path = ArrayList<Int>(); var cursor = goal; path += cursor
            while (parent[cursor] >= 0) { cursor = parent[cursor]; path += cursor }
            path.reverse()
            return PortalSearchResult(path, origin[goal], cost[goal], distance[goal], expanded)
        }
        if (expanded >= maxExpandedNodes) return PortalSearchResult(emptyList(), null, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, expanded, wasTruncated = true)
        edges.reset(node)
        while (edges.next()) {
            val target = edges.target
            if (target < 0) continue
            val multiplier = routingEdgeMultiplier(edges.type, edges.grade, preference, costMode)
            if (multiplier.isNaN()) continue
            val next = cost[node] + edges.distance * multiplier
            if (next < cost[target]) {
                cost[target] = next; distance[target] = distance[node] + edges.distance
                parent[target] = node; origin[target] = origin[node]
                heap.push(target, next, next + straightLineHeuristic(target, goal))
            }
        }
    }
    return PortalSearchResult(emptyList(), null, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, expanded)
}

fun routeAcrossGraphs(
    a: LazyMappedRoadGraph,
    startA: Int,
    b: LazyMappedRoadGraph,
    goalB: Int,
    portals: CrossPortals,
    preference: RoutePreference = RoutePreference.RECOMMENDED,
    isCancelled: (() -> Boolean)? = null,
    maxExpandedNodes: Int = Int.MAX_VALUE,
    costMode: RouteCostMode = RouteCostMode.BICYCLE,
): CrossRouteResult {
    require(portals.aIndices.size == portals.bIndices.size && portals.nodeIds.size == portals.aIndices.size)
    val reach = a.routeToPortals(startA, portals.aIndices, preference, isCancelled, maxExpandedNodes, costMode)
    if (reach.wasCancelled || reach.wasTruncated) return CrossRouteResult(emptyList(), emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, CrossRoutePart(), CrossRoutePart(), null, reach.wasCancelled, reach.wasTruncated)
    val byIndex = reach.portals.associateBy { it.aIndex }
    val sources = portals.nodeIds.indices.mapNotNull { k -> byIndex[portals.aIndices[k]]?.let { PortalSource(it.nodeId, it.aIndex, portals.bIndices[k], it.cost, it.distanceMeters) } }
    val bResult = b.routeFromPortals(sources, goalB, preference, isCancelled, maxExpandedNodes, costMode)
    if (bResult.wasCancelled || bResult.wasTruncated || !bResult.isReachable) return CrossRouteResult(emptyList(), emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, CrossRoutePart(), CrossRoutePart(), bResult.seedPortalId, bResult.wasCancelled, bResult.wasTruncated)
    val selected = sources.first { it.nodeId == bResult.seedPortalId }
    val aIndices = ArrayList<Int>(); var c = selected.aIndex; aIndices += c
    while (reach.parentSnapshot[c] >= 0) { c = reach.parentSnapshot[c]; aIndices += c }
    aIndices.reverse()
    val aIds = aIndices.map { a.nodeAt(it).id }
    val aCoords = aIndices.map { val n = a.nodeAt(it); n.latitude to n.longitude }
    val bIndices = bResult.nodeIndices
    val bIds = bIndices.map { b.nodeAt(it).id }
    val bCoords = bIndices.map { val n = b.nodeAt(it); n.latitude to n.longitude }
    val fullIds = aIds + bIds.drop(1)
    val fullCoords = aCoords + bCoords.drop(1)
    return CrossRouteResult(fullIds, fullCoords, bResult.totalDistanceMeters, bResult.totalCost,
        CrossRoutePart(aIds, aCoords, aIndices), CrossRoutePart(bIds, bCoords, bIndices), selected.nodeId)
}

private fun LazyMappedRoadGraph.straightLineHeuristic(from: Int, goal: Int): Double {
    return portalHeuristic(from, goal)
}

/** 成長するプリミティブ二分ヒープ。key が A* の f、g は stale entry 判定用。 */
private class PrimitiveHeap {
    private var nodes = IntArray(1024)
    private var gs = DoubleArray(1024)
    private var keys = DoubleArray(1024)
    private var size = 0
    val isEmpty get() = size == 0
    var node: Int = -1; private set
    var g: Double = Double.NaN; private set
    fun push(n: Int, gv: Double, key: Double) {
        if (size == nodes.size) { nodes = nodes.copyOf(size * 2); gs = gs.copyOf(size * 2); keys = keys.copyOf(size * 2) }
        var i = size++
        while (i > 0) { val p = (i - 1) ushr 1; if (keys[p] <= key) break; nodes[i] = nodes[p]; gs[i] = gs[p]; keys[i] = keys[p]; i = p }
        nodes[i] = n; gs[i] = gv; keys[i] = key
    }
    fun pop() {
        node = nodes[0]; g = gs[0]; size--
        if (size == 0) return
        val n = nodes[size]; val gv = gs[size]; val key = keys[size]
        var i = 0
        while (true) { var ch = i * 2 + 1; if (ch >= size) break; if (ch + 1 < size && keys[ch + 1] < keys[ch]) ch++; if (keys[ch] >= key) break; nodes[i] = nodes[ch]; gs[i] = gs[ch]; keys[i] = keys[ch]; i = ch }
        nodes[i] = n; gs[i] = gv; keys[i] = key
    }
}
