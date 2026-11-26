package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.JunctionName
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteSegmentGrade
import com.gorite.cyclemap.routing.TurnClassifier
import com.gorite.cyclemap.routing.bearingBetween

/** A/B グラフから取得した、統合経路用の勾配・標高。 */
internal data class CrossRouteElevationData(
    val segments: List<RouteSegmentGrade>,
    val nodeElevations: List<Double?>,
)

internal data class CrossInstructionPart(
    val instructions: List<RouteInstruction>,
    val nodeCount: Int,
    val distanceMeters: Double,
    val entry: CrossPortalSide? = null,
    val exit: CrossPortalSide? = null,
)

/** 一方の県グラフが持つ通り口の経路辺と分岐。 */
internal data class CrossPortalSide(
    val nodeId: Long,
    val point: RoutePoint,
    val adjacentNodeId: Long,
    val adjacentPoint: RoutePoint,
    val routeEdge: LazyMappedRoadGraph.JunctionBranch?,
    val branches: List<TurnClassifier.MappedBranch>,
    val junction: JunctionName? = null,
)

internal data class CrossElevationPart(
    val segments: List<RouteSegmentGrade>,
    val nodeElevations: List<Double?>,
)

/** 同じグラフ内で続く区間を連結して、経由地を含む案内を組み立てる。 */
internal fun buildCrossInstructionPart(
    graph: LazyMappedRoadGraph,
    parts: List<CrossRoutePathPart>,
): CrossInstructionPart {
    val path = if (parts.size == 1) parts.single() else {
        val merged = mergeRoutePathParts(parts)
        CrossRoutePathPart(merged.nodeIds, merged.coordinates, merged.distanceMeters, merged.cost)
    }
    val firstEdge = parts.firstOrNull { it.nodeIndices.size >= 2 }?.nodeIndices
    val lastEdge = parts.lastOrNull { it.nodeIndices.size >= 2 }?.nodeIndices
    return CrossInstructionPart(
        // 県境の案内も含め、全区間の累積距離が確定してから重複を除く。
        instructions = buildRouteInstructions(graph, path.nodeIds, path.coordinates, deduplicateJunctionNames = false),
        nodeCount = path.nodeIds.size,
        distanceMeters = path.distanceMeters,
        entry = firstEdge?.let { readPortalSide(graph, it[0], it[1], atStart = true) },
        exit = lastEdge?.let { readPortalSide(graph, it.last(), it[it.lastIndex - 1], atStart = false) },
    )
}

private fun readPortalSide(
    graph: LazyMappedRoadGraph,
    portalIndex: Int,
    adjacentIndex: Int,
    atStart: Boolean,
): CrossPortalSide {
    val portal = graph.nodeAt(portalIndex)
    val adjacent = graph.nodeAt(adjacentIndex)
    val portalBranches = graph.outgoingBranches(portalIndex)
    val routeEdge = if (atStart) {
        portalBranches.firstOrNull { it.targetIndex == adjacentIndex }
    } else {
        graph.outgoingBranches(adjacentIndex).firstOrNull { it.targetIndex == portalIndex }
    }
    val branches = portalBranches.mapNotNull { branch ->
        val target = graph.nodeAt(branch.targetIndex)
        val bearing = bearingBetween(portal.latitude, portal.longitude, target.latitude, target.longitude)
            ?: return@mapNotNull null
        TurnClassifier.MappedBranch(bearing, branch.roadTypeName, branch.distanceMeters, target.id)
    }
    return CrossPortalSide(
        portal.id, RoutePoint(portal.latitude, portal.longitude),
        adjacent.id, RoutePoint(adjacent.latitude, adjacent.longitude), routeEdge, branches,
        junction = graph.junctionNameNear(portalIndex),
    )
}

private fun classifyCrossPortal(
    incoming: CrossPortalSide,
    outgoing: CrossPortalSide,
    routeIndex: Int,
    distanceFromStart: Double,
): RouteInstruction? {
    if (incoming.nodeId != outgoing.nodeId) return null
    // 県境の両側にある同一分岐は、進行先の県の枝を優先して一つにする。
    val branches = (outgoing.branches + incoming.branches).distinctBy { it.targetNodeId }
    return TurnClassifier.classifyMappedRoute(
        nodeIds = listOf(incoming.adjacentNodeId, incoming.nodeId, outgoing.adjacentNodeId),
        coordinates = listOf(incoming.adjacentPoint, incoming.point, outgoing.adjacentPoint),
        branchesOf = { branches },
        edgeTypes = listOf(incoming.routeEdge?.roadTypeName ?: "unknown", outgoing.routeEdge?.roadTypeName ?: "unknown"),
    ).singleOrNull()?.takeUnless { it.type == InstructionType.STRAIGHT }?.copy(
        routeIndex = routeIndex,
        distanceFromStartMeters = distanceFromStart,
        outgoingRoadName = outgoing.routeEdge?.roadName,
        incomingTurnLanes = incoming.routeEdge?.turnLanes,
    )?.withJunctionName(outgoing.junction ?: incoming.junction)
}

/** A/B の案内を統合経路の routeIndex と距離に変換する。境界ポータルのB側案内は重複させない。 */
internal fun mergeInstructionParts(
    aInstructions: List<RouteInstruction>,
    bInstructions: List<RouteInstruction>,
    aNodeCount: Int,
    aDistanceMeters: Double,
): List<RouteInstruction> {
    return mergeInstructionParts(listOf(
        CrossInstructionPart(aInstructions, aNodeCount, aDistanceMeters),
        CrossInstructionPart(bInstructions, Int.MAX_VALUE, 0.0),
    ))
}

/** 任意数の連続グラフ部品を全体 routeIndex / 累積距離へ変換する。 */
internal fun mergeInstructionParts(parts: List<CrossInstructionPart>): List<RouteInstruction> {
    val out = ArrayList<RouteInstruction>()
    var nodeOffset = 0
    var distanceOffset = 0.0
    var previousExit: CrossPortalSide? = null
    parts.forEachIndexed { i, part ->
        if (part.nodeCount <= 0) return@forEachIndexed
        val skipBoundary = if (i == 0) 0 else 1
        val incoming = previousExit
        val outgoing = part.entry
        if (incoming != null && outgoing != null) {
            classifyCrossPortal(incoming, outgoing, nodeOffset - 1, distanceOffset)?.let(out::add)
        }
        // 通り口1ノードだけの区間を挟む場合も、前後の経路辺を使って判定する。
        if (part.nodeCount > 1) previousExit = part.exit
        part.instructions.asSequence()
            .filter { it.routeIndex >= skipBoundary && it.routeIndex < part.nodeCount }
            .mapTo(out) { it.copy(
                routeIndex = it.routeIndex + nodeOffset - skipBoundary,
                distanceFromStartMeters = it.distanceFromStartMeters + distanceOffset,
            ) }
        nodeOffset += (part.nodeCount - skipBoundary).coerceAtLeast(0)
        distanceOffset += part.distanceMeters
    }
    val sorted = out.sortedWith(compareBy<RouteInstruction> { it.routeIndex }.thenBy { it.distanceFromStartMeters })
    // 県境の案内とその前後の区間の案内が、同じ交差点の中で細切れになることもあるので、ここでも整える
    return suppressRepeatedJunctionNames(cleanUpInstructions(sorted))
}

/** 同一ポータルを1ノードとしてA/Bの勾配・標高列を連結する。 */
internal fun mergeElevationParts(
    aSegments: List<RouteSegmentGrade>,
    aElevations: List<Double?>,
    bSegments: List<RouteSegmentGrade>,
    bElevations: List<Double?>,
): CrossRouteElevationData {
    return mergeElevationParts(listOf(CrossElevationPart(aSegments, aElevations), CrossElevationPart(bSegments, bElevations)))
}

/** 任意数の勾配・標高部品を境界ノードを重複させず連結する。 */
internal fun mergeElevationParts(parts: List<CrossElevationPart>): CrossRouteElevationData {
    val segments = ArrayList<RouteSegmentGrade>()
    val elevations = ArrayList<Double?>()
    parts.forEach { part ->
        require(part.nodeElevations.isEmpty() || part.segments.size == part.nodeElevations.size - 1)
        segments.addAll(part.segments)
        if (part.nodeElevations.isNotEmpty()) {
            elevations.addAll(part.nodeElevations.drop(if (elevations.isEmpty()) 0 else 1))
        }
    }
    require(elevations.isEmpty() || segments.size == (elevations.size - 1).coerceAtLeast(0))
    return CrossRouteElevationData(segments, elevations)
}
