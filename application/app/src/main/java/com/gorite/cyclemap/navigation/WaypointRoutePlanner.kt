package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.MappedRouteResult
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.routing.TrimmedRoute
import com.gorite.cyclemap.routing.haversineMeters
import com.gorite.cyclemap.routing.routeWithSnapFallback
import com.gorite.cyclemap.routing.trimRouteEnds

internal sealed interface SingleGraphRoutePlan {
    data class Success(
        val result: MappedRouteResult,
        val firstStartIndex: Int,
        val lastGoalIndex: Int,
        val legEngines: List<String>,
        val waypointNodeIds: List<Long>,
    ) : SingleGraphRoutePlan

    data class WaypointUnreachable(val waypointNumber: Int) : SingleGraphRoutePlan
    data object Truncated : SingleGraphRoutePlan
    data object Cancelled : SingleGraphRoutePlan
    data object Unreachable : SingleGraphRoutePlan
}

/**
 * 経由地の代替ノード候補を、最寄りノード近傍の現実的な範囲（平行道路・反対車線など）に絞る。
 * 袋小路に寄れないときに遠くの別交差点や始点・終点ノードへ逃げて「寄ったことにする」のを防ぐ。
 */
internal fun filterWaypointCandidates(
    graph: LazyMappedRoadGraph,
    lat: Double,
    lon: Double,
    rawCandidates: IntArray,
): IntArray {
    if (rawCandidates.isEmpty()) return rawCandidates
    val nearest = graph.nodeAt(rawCandidates[0])
    val nearestDist = haversineMeters(lat, lon, nearest.latitude, nearest.longitude)
    val maxRadius = maxOf(nearestDist + 60.0, 80.0).coerceAtMost(400.0)
    return rawCandidates.filterIndexed { idx, nodeIdx ->
        if (idx == 0) true
        else {
            val n = graph.nodeAt(nodeIdx)
            haversineMeters(lat, lon, n.latitude, n.longitude) <= maxRadius
        }
    }.toIntArray()
}

/**
 * 単一グラフ上で出発地・経由地・目的地を結ぶ連続した経路を計画する。
 *
 * 経由地がある場合、前の区間が終わったノードから次の区間を始めることで、
 * 経由地の前後が別の道路（例: 一方通行の袋小路と平行道路）にスナップして途切れるのを防ぐ。
 */
internal fun planSingleGraphRoute(
    graph: LazyMappedRoadGraph,
    stops: List<Pair<Double, Double>>,
    preference: RoutePreference = RoutePreference.RECOMMENDED,
    isCancelled: () -> Boolean = { false },
    maxExpandedNodes: Int = Int.MAX_VALUE,
    maxStartCandidates: Int = 24,
    maxWaypointCandidates: Int = 24,
    maxGoalCandidates: Int = 6,
    candidateRadiusMeters: Double = 400.0,
): SingleGraphRoutePlan {
    if (stops.size < 2 || graph.nodeCount == 0) return SingleGraphRoutePlan.Unreachable

    if (stops.size == 2) {
        val outcome = graph.routeWithSnapFallback(
            stops[0].first, stops[0].second,
            stops[1].first, stops[1].second,
            isCancelled = isCancelled,
            maxExpandedNodes = maxExpandedNodes,
            preference = preference,
            maxStartCandidates = maxStartCandidates,
            maxGoalCandidates = maxGoalCandidates,
        )
        val r = outcome.result
        return when {
            r.wasCancelled -> SingleGraphRoutePlan.Cancelled
            r.wasTruncated -> SingleGraphRoutePlan.Truncated
            r.isReachable -> SingleGraphRoutePlan.Success(
                result = r,
                firstStartIndex = outcome.startIndex,
                lastGoalIndex = outcome.goalIndex,
                legEngines = listOf(graph.lastEngine),
                waypointNodeIds = emptyList(),
            )
            else -> SingleGraphRoutePlan.Unreachable
        }
    }

    val startNode0 = graph.nearestNodeCandidates(
        stops.first().first,
        stops.first().second,
        radiusMeters = candidateRadiusMeters,
        maxCount = 1,
    ).firstOrNull() ?: -1
    val goalNode0 = graph.nearestNodeCandidates(
        stops.last().first,
        stops.last().second,
        radiusMeters = candidateRadiusMeters,
        maxCount = 1,
    ).firstOrNull() ?: -1
    if (startNode0 < 0 || goalNode0 < 0) return SingleGraphRoutePlan.Unreachable

    val waypointCandidates = Array(stops.size) { i ->
        if (i == 0 || i == stops.lastIndex) IntArray(0)
        else {
            val (lat, lon) = stops[i]
            val raw = graph.nearestNodeCandidates(
                lat,
                lon,
                radiusMeters = candidateRadiusMeters,
                maxCount = maxWaypointCandidates,
            )
            filterWaypointCandidates(graph, lat, lon, raw)
        }
    }
    for (i in 1 until stops.lastIndex) {
        if (waypointCandidates[i].isEmpty()) return SingleGraphRoutePlan.WaypointUnreachable(i)
    }

    var sawTruncated = false
    var maxReachedLeg = -1
    var bestReachedAtLeg = Array(stops.size) { LinkedHashSet<Int>() }

    fun tryConnect(startNode: Int, goalNode: Int): SingleGraphRoutePlan? {
        val chosenNodes = IntArray(stops.size)
        val legResults = arrayOfNulls<MappedRouteResult>(stops.size - 1)
        val legEngines = arrayOfNulls<String>(stops.size - 1)
        val failedFrom = Array(stops.size) { HashSet<Int>() }
        val reachedAtLeg = Array(stops.size) { LinkedHashSet<Int>() }
        var localMaxReached = -1

        fun searchFrom(leg: Int, currentStart: Int): Int {
            if (isCancelled()) return 1
            if (currentStart in failedFrom[leg]) return -1
            chosenNodes[leg] = currentStart
            val nextStop = leg + 1
            val nextCands = if (nextStop == stops.lastIndex) intArrayOf(goalNode) else waypointCandidates[nextStop]
            for (nextNode in nextCands) {
                if (isCancelled()) return 1
                if (nextStop < stops.lastIndex && nextNode in failedFrom[nextStop]) continue
                val r = graph.route(
                    currentStart,
                    nextNode,
                    isCancelled = isCancelled,
                    maxExpandedNodes = maxExpandedNodes,
                    preference = preference,
                )
                if (r.wasCancelled) return 1
                if (r.wasTruncated) {
                    sawTruncated = true
                    continue
                }
                if (!r.isReachable) continue
                legResults[leg] = r
                legEngines[leg] = graph.lastEngine
                reachedAtLeg[nextStop].add(nextNode)
                if (leg > localMaxReached) localMaxReached = leg
                if (nextStop == stops.lastIndex) {
                    chosenNodes[nextStop] = nextNode
                    return 0
                }
                val sub = searchFrom(leg + 1, nextNode)
                if (sub != -1) return sub
            }
            failedFrom[leg].add(currentStart)
            return -1
        }

        val status = searchFrom(0, startNode)
        if (localMaxReached > maxReachedLeg) {
            maxReachedLeg = localMaxReached
            bestReachedAtLeg = reachedAtLeg
        }
        return when (status) {
            1 -> SingleGraphRoutePlan.Cancelled
            0 -> {
                val legs = legResults.map { it!! }
                val merged = mergeSingleGraphLegs(legs)
                val waypointNodeIds = (1 until stops.lastIndex).map { graph.nodeAt(chosenNodes[it]).id }
                SingleGraphRoutePlan.Success(
                    result = merged,
                    firstStartIndex = chosenNodes.first(),
                    lastGoalIndex = chosenNodes.last(),
                    legEngines = legEngines.filterNotNull(),
                    waypointNodeIds = waypointNodeIds,
                )
            }
            else -> null
        }
    }

    tryConnect(startNode0, goalNode0)?.let { return it }

    // 最寄りの始点・終点でつながらない場合のみ、孤立道のフォールバック後に同じ経由地探索を試す。
    val direct = graph.routeWithSnapFallback(
        stops.first().first, stops.first().second,
        stops.last().first, stops.last().second,
        isCancelled = isCancelled,
        maxExpandedNodes = maxExpandedNodes,
        preference = preference,
        maxStartCandidates = maxStartCandidates,
        maxGoalCandidates = maxGoalCandidates,
    )
    if (direct.result.wasCancelled) return SingleGraphRoutePlan.Cancelled
    if (!direct.result.isReachable) {
        return if (sawTruncated) SingleGraphRoutePlan.Truncated else SingleGraphRoutePlan.Unreachable
    }
    if (direct.usedFallbackSnap && (direct.startIndex != startNode0 || direct.goalIndex != goalNode0)) {
        tryConnect(direct.startIndex, direct.goalIndex)?.let { return it }
    }
    if (sawTruncated) return SingleGraphRoutePlan.Truncated

    if (maxReachedLeg < 0) {
        return SingleGraphRoutePlan.WaypointUnreachable(1)
    }
    val stalledWaypoint = maxReachedLeg + 1
    if (stalledWaypoint >= stops.lastIndex - 1) {
        return SingleGraphRoutePlan.WaypointUnreachable(stalledWaypoint)
    }
    // W_k までは到達できたが W_{k+1} へ進めないとき、W_k からゴール方面に出られるなら原因は W_{k+1} 側。
    val canLeaveStalledWaypoint = bestReachedAtLeg[stalledWaypoint].any { wIdx ->
        val r = graph.route(
            wIdx,
            direct.goalIndex,
            isCancelled = isCancelled,
            maxExpandedNodes = maxExpandedNodes,
            preference = preference,
        )
        if (r.wasCancelled) return SingleGraphRoutePlan.Cancelled
        r.isReachable
    }
    return SingleGraphRoutePlan.WaypointUnreachable(
        if (canLeaveStalledWaypoint) stalledWaypoint + 1 else stalledWaypoint,
    )
}

/** 連続する複数の単一グラフ区間を、接続ノードを重複させずに 1 本の [MappedRouteResult] へ連結する。 */
internal fun mergeSingleGraphLegs(legs: List<MappedRouteResult>): MappedRouteResult {
    require(legs.isNotEmpty()) { "legs must not be empty" }
    if (legs.size == 1) return legs.single()

    val allNodeIds = ArrayList<Long>()
    val allNodeIndices = ArrayList<Int>()
    val allCoordinates = ArrayList<Pair<Double, Double>>()
    var totalDistance = 0.0
    var totalCost = 0.0
    var anyTruncated = false

    for (leg in legs) {
        if (leg.wasTruncated) anyTruncated = true
        totalDistance += leg.totalDistanceMeters
        totalCost += leg.totalCost
        if (allCoordinates.isEmpty()) {
            allCoordinates.addAll(leg.coordinates)
            allNodeIds.addAll(leg.nodeIds)
            allNodeIndices.addAll(leg.nodeIndices)
        } else {
            allCoordinates.addAll(leg.coordinates.drop(1))
            allNodeIds.addAll(leg.nodeIds.drop(1))
            allNodeIndices.addAll(leg.nodeIndices.drop(1))
        }
    }
    return MappedRouteResult(
        nodeIds = allNodeIds,
        totalDistanceMeters = totalDistance,
        totalCost = totalCost,
        coordinates = allCoordinates,
        wasCancelled = false,
        wasTruncated = anyTruncated,
        nodeIndices = if (allNodeIndices.size == allNodeIds.size) allNodeIndices else emptyList(),
    )
}

/**
 * 起点・終点の端辺射影補正を適用する。経由地がある場合に補正で経由地ノードが落ちるのを防ぐ。
 */
internal fun trimSingleGraphRouteSafe(
    rawResult: MappedRouteResult,
    stops: List<Pair<Double, Double>>,
    waypointNodeIds: List<Long> = emptyList(),
): TrimmedRoute? {
    if (!rawResult.isReachable || stops.size < 2) return null
    val trimmed = trimRouteEnds(
        rawResult,
        stops.first().first, stops.first().second,
        stops.last().first, stops.last().second,
    )
    if (waypointNodeIds.isEmpty()) return trimmed
    return if (containsAllInOrder(trimmed.result.nodeIds, waypointNodeIds)) {
        trimmed
    } else {
        TrimmedRoute(rawResult, emptyList(), emptyList())
    }
}

private fun containsAllInOrder(nodeIds: List<Long>, expected: List<Long>): Boolean {
    var pos = 0
    for (target in expected) {
        var found = false
        while (pos < nodeIds.size) {
            if (nodeIds[pos] == target) {
                found = true
                break
            }
            pos++
        }
        if (!found) return false
    }
    return true
}
