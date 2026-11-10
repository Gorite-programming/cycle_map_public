package com.gorite.cyclemap.routing

/** A graph source. Borrowed graphs are returned by [open] and are never closed by the chain router. */
data class ChainGraph(
    val open: () -> LazyMappedRoadGraph,
    val close: (LazyMappedRoadGraph) -> Unit = { it.close() },
    val borrowed: Boolean = false,
)

data class CrossChainResult(
    val nodeIds: List<Long>,
    val coordinates: List<Pair<Double, Double>>,
    val totalDistanceMeters: Double,
    val totalCost: Double,
    val parts: List<CrossRoutePart>,
    val portalIds: List<Long>,
    val wasCancelled: Boolean = false,
    val wasTruncated: Boolean = false,
    val stageSearchMs: List<Long> = emptyList(),
    val stageNodeCounts: List<Int> = emptyList(),
) {
    val isReachable: Boolean get() = nodeIds.isNotEmpty() && !wasCancelled && !wasTruncated
}

internal data class ChainPortalCost(val index: Int, val cost: Double, val distance: Double, val originIndex: Int)
internal data class ChainPortalReach(
    val portals: List<ChainPortalCost>,
    val pathToSolePortal: IntArray?,
    val expandedNodes: Int,
    val wasCancelled: Boolean,
    val wasTruncated: Boolean,
)

private data class ChainStageSnapshot(
    val ids: LongArray,
    val indices: IntArray,
)

private data class ChainSeed(val id: Long, val index: Int, val cost: Double, val distance: Double)

/** Finds a least-cost route through the supplied graph order. At most two owned graphs are open at once. */
fun routeAcrossChain(
    graphs: List<ChainGraph>,
    startIndex: Int,
    goalIndex: Int,
    preference: RoutePreference = RoutePreference.RECOMMENDED,
    isCancelled: (() -> Boolean)? = null,
    maxExpandedNodes: Int = Int.MAX_VALUE,
    costMode: RouteCostMode = RouteCostMode.BICYCLE,
): CrossChainResult {
    require(graphs.size >= 3) { "A chain requires at least three graphs" }
    require(maxExpandedNodes > 0)
    val stageMs = MutableList(graphs.size) { 0L }
    val nodeCounts = MutableList(graphs.size) { 0 }
    val snapshots = arrayOfNulls<ChainStageSnapshot>(graphs.size - 1)
    val stageInputs = arrayOfNulls<List<ChainSeed>>(graphs.size)
    var seeds: List<ChainSeed> = emptyList()

    fun acquire(i: Int) = graphs[i].open().also { if (graphs[i].borrowed) it.clearWorkspaceCache() }
    fun release(i: Int, graph: LazyMappedRoadGraph) { if (!graphs[i].borrowed) graphs[i].close(graph) }
    fun failed(cancelled: Boolean = false, truncated: Boolean = false) = CrossChainResult(
        emptyList(), emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, emptyList(), emptyList(),
        cancelled, truncated, stageMs.toList(), nodeCounts.toList(),
    )

    for (i in 0 until graphs.lastIndex) {
        val started = System.nanoTime()
        val current = acquire(i)
        var next: LazyMappedRoadGraph? = null
        try {
            next = acquire(i + 1)
            val portals = portalsBetween(current, next)
            if (portals.size == 0) { stageMs[i] = (System.nanoTime() - started) / 1_000_000; return failed() }
            // The next graph is needed only to find the portal pairs. Search this stage
            // with the current graph alone so two large owned graphs are not live together.
            if (!graphs[i + 1].borrowed) {
                release(i + 1, next)
                next = null
                System.gc()
            }
            if (i == 0) {
                val reach = current.routeToPortals(startIndex, portals.aIndices, preference, isCancelled, maxExpandedNodes, costMode,
                    includeParentSnapshot = false)
                nodeCounts[i] = reach.expandedNodes
                if (reach.wasCancelled || reach.wasTruncated) {
                    stageMs[i] = (System.nanoTime() - started) / 1_000_000
                    return failed(reach.wasCancelled, reach.wasTruncated)
                }
                val costByIndex = reach.portals.associateBy { it.aIndex }
                seeds = portals.nodeIds.indices.mapNotNull { p ->
                    costByIndex[portals.aIndices[p]]?.let { ChainSeed(it.nodeId, portals.bIndices[p], it.cost, it.distanceMeters) }
                }
                snapshots[0] = ChainStageSnapshot(
                    LongArray(reach.portals.size) { reach.portals[it].nodeId },
                    IntArray(reach.portals.size) { reach.portals[it].aIndex },
                )
            } else {
                stageInputs[i] = seeds
                val sources = seeds.map { PortalSource(it.id, it.index, -1, it.cost, it.distance) }
                val reach = current.routeBetweenPortals(sources, portals.aIndices, preference, isCancelled, maxExpandedNodes, costMode)
                nodeCounts[i] = reach.expandedNodes
                if (reach.wasCancelled || reach.wasTruncated) {
                    stageMs[i] = (System.nanoTime() - started) / 1_000_000
                    return failed(reach.wasCancelled, reach.wasTruncated)
                }
                if (reach.portals.isEmpty()) {
                    stageMs[i] = (System.nanoTime() - started) / 1_000_000
                    return failed()
                }
                val foundByIndex = reach.portals.associateBy { it.index }
                val outSeeds = ArrayList<ChainSeed>()
                val outIds = ArrayList<Long>(); val outIndices = ArrayList<Int>()
                for (p in portals.nodeIds.indices) {
                    val found = foundByIndex[portals.aIndices[p]] ?: continue
                    outSeeds += ChainSeed(portals.nodeIds[p], portals.bIndices[p], found.cost, found.distance)
                    outIds += portals.nodeIds[p]; outIndices += found.index
                }
                seeds = outSeeds
                snapshots[i] = ChainStageSnapshot(outIds.toLongArray(), outIndices.toIntArray())
            }
            stageMs[i] = (System.nanoTime() - started) / 1_000_000
        } finally {
            try { if (next != null) release(i + 1, next) }
            finally {
                release(i, current)
                if (graphs[i].borrowed) current.clearWorkspaceCache()
            }
        }
        // Reopened graph objects retain large heap arrays even after their mmap is closed. Reclaim
        // those arrays before opening the next pair so peak Java heap tracks the two-live-graph bound.
        if (!graphs[i].borrowed || !graphs[i + 1].borrowed) System.gc()
    }

    val finalStarted = System.nanoTime()
    val last = acquire(graphs.lastIndex)
    val finalResult: PortalSearchResult
    try {
        val sources = seeds.map { PortalSource(it.id, -1, it.index, it.cost, it.distance) }
        finalResult = last.routeFromPortals(sources, goalIndex, preference, isCancelled, maxExpandedNodes, costMode)
        nodeCounts[nodeCounts.lastIndex] = finalResult.expandedNodes
    } finally { release(graphs.lastIndex, last) }
    stageMs[stageMs.lastIndex] = (System.nanoTime() - finalStarted) / 1_000_000
    if (finalResult.wasCancelled || finalResult.wasTruncated || !finalResult.isReachable) {
        return failed(finalResult.wasCancelled, finalResult.wasTruncated)
    }

    val partByStage = arrayOfNulls<CrossRoutePart>(graphs.size)
    run {
        val graph = acquire(graphs.lastIndex)
        try { partByStage[graphs.lastIndex] = makePart(graph, finalResult.nodeIndices) }
        finally { release(graphs.lastIndex, graph) }
    }
    if (!graphs.last().borrowed) System.gc()
    val selectedPortals = ArrayList<Long>()
    var selectedId = finalResult.seedPortalId ?: return failed()
    selectedPortals += selectedId
    for (i in graphs.lastIndex - 1 downTo 1) {
        val snapshot = snapshots[i] ?: return failed()
        val exitSlot = snapshot.ids.indexOf(selectedId)
        if (exitSlot < 0) return failed()
        val incoming = stageInputs[i] ?: return failed()
        val sources = incoming.map { PortalSource(it.id, it.index, -1, it.cost, it.distance) }
        val graph = acquire(i)
        try {
            val reach = graph.routeBetweenPortals(sources, intArrayOf(snapshot.indices[exitSlot]), preference,
                isCancelled, maxExpandedNodes, costMode)
            if (reach.wasCancelled || reach.wasTruncated || reach.portals.isEmpty()) return failed(reach.wasCancelled, reach.wasTruncated)
            val path = reach.pathToSolePortal ?: return failed()
            partByStage[i] = makePart(graph, path.toList())
            selectedId = graph.nodeAt(reach.portals.single().originIndex).id
        }
        finally { release(i, graph) }
        if (!graphs[i].borrowed) System.gc()
        selectedPortals += selectedId
    }
    val firstSnapshot = snapshots[0] ?: return failed()
    val firstSlot = firstSnapshot.ids.indexOf(selectedId)
    if (firstSlot < 0) return failed()
    val firstTarget = firstSnapshot.indices[firstSlot]
    val firstGraph = acquire(0)
    try {
        val reach = firstGraph.routeToPortals(startIndex, intArrayOf(firstTarget), preference,
            isCancelled, maxExpandedNodes, costMode, includeParentSnapshot = false)
        if (reach.wasCancelled || reach.wasTruncated || reach.portals.isEmpty()) return failed(reach.wasCancelled, reach.wasTruncated)
        val path = reach.pathToSolePortal ?: return failed()
        partByStage[0] = makePart(firstGraph, path.toList())
    }
    finally { release(0, firstGraph) }

    val parts = partByStage.map { it ?: CrossRoutePart() }
    val ids = ArrayList<Long>(); val coords = ArrayList<Pair<Double, Double>>()
    for (part in parts) {
        val skip = if (ids.isNotEmpty() && ids.last() == part.nodeIds.firstOrNull()) 1 else 0
        ids += part.nodeIds.drop(skip); coords += part.coordinates.drop(skip)
    }
    selectedPortals.reverse()
    return CrossChainResult(ids, coords, finalResult.totalDistanceMeters, finalResult.totalCost, parts, selectedPortals,
        stageSearchMs = stageMs.toList(), stageNodeCounts = nodeCounts.toList())
}

private fun makePart(graph: LazyMappedRoadGraph, indices: List<Int>): CrossRoutePart = CrossRoutePart(
    indices.map { graph.nodeAt(it).id }, indices.map { val n = graph.nodeAt(it); n.latitude to n.longitude }, indices,
)

