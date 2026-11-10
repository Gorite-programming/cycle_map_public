package com.gorite.cyclemap.routing

/** A graph source. Borrowed graphs are returned by [open] and are never closed by the chain router. */
data class ChainGraph(
    val open: () -> LazyMappedRoadGraph,
    val close: (LazyMappedRoadGraph) -> Unit = { it.close() },
    val borrowed: Boolean = false,
    /** Optional ID-only access path used to find portals without opening the neighbor's routing graph. */
    val openIdIndex: (() -> SortedNodeIdIndex)? = null,
    /** Human-readable label used by optional stage timing logs. */
    val stageName: String? = null,
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
    /** For each exit portal, the entry portal selected by the forward multi-source search. */
    val seedIds: LongArray,
    /** Matching entry portal indices avoid rebuilding the large node-ID lookup during restoration. */
    val seedIndices: IntArray,
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
    stageTimingSink: ((String) -> Unit)? = null,
): CrossChainResult {
    require(graphs.size >= 3) { "A chain requires at least three graphs" }
    require(maxExpandedNodes > 0)
    val stageMs = MutableList(graphs.size) { 0L }
    val nodeCounts = MutableList(graphs.size) { 0 }
    val snapshots = arrayOfNulls<ChainStageSnapshot>(graphs.size - 1)
    var seeds: List<ChainSeed> = emptyList()
    val graphOpenNanos = LongArray(graphs.size)
    var portalMatchNanos = 0L
    var startSearchNanos = 0L
    var middleSearchNanos = 0L
    var goalSearchNanos = 0L
    var reconstructionNanos = 0L

    fun acquire(i: Int) = graphs[i].open().also { if (graphs[i].borrowed) it.clearWorkspaceCache() }
    fun timedAcquire(i: Int): LazyMappedRoadGraph {
        val started = System.nanoTime()
        return try { acquire(i) } finally { graphOpenNanos[i] += System.nanoTime() - started }
    }
    fun emitStages() {
        val sink = stageTimingSink ?: return
        fun emit(name: String, nanos: Long) { sink("CHAIN_STAGE name=$name ms=${nanos / 1_000_000}") }
        emit("portal_match", portalMatchNanos)
        graphOpenNanos.forEachIndexed { i, nanos -> emit("graph_open_${graphs[i].stageName ?: i}", nanos) }
        emit("start_search", startSearchNanos)
        emit("middle_dijkstra", middleSearchNanos)
        emit("goal_search", goalSearchNanos)
        emit("reconstruction", reconstructionNanos)
    }

    fun release(i: Int, graph: LazyMappedRoadGraph) { if (!graphs[i].borrowed) graphs[i].close(graph) }
    fun failed(cancelled: Boolean = false, truncated: Boolean = false): CrossChainResult {
        emitStages()
        return CrossChainResult(
            emptyList(), emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, emptyList(), emptyList(),
            cancelled, truncated, stageMs.toList(), nodeCounts.toList(),
        )
    }

    for (i in 0 until graphs.lastIndex) {
        val started = System.nanoTime()
        val current = timedAcquire(i)
        var next: LazyMappedRoadGraph? = null
        try {
            val hasIdOnlyPath = graphs[i + 1].openIdIndex != null
            val portalStarted = System.nanoTime()
            var nextIdIndex = graphs[i + 1].openIdIndex?.invoke()
            val portals = if (nextIdIndex != null) {
                portalsBetween(current, nextIdIndex)
            } else {
                next = timedAcquire(i + 1)
                portalsBetween(current, next!!)
            }
            portalMatchNanos += System.nanoTime() - portalStarted
            nextIdIndex = null
            if (hasIdOnlyPath && !graphs[i + 1].borrowed) System.gc()
            if (portals.size == 0) { stageMs[i] = (System.nanoTime() - started) / 1_000_000; return failed() }
            // The next graph is needed only to find the portal pairs. Search this stage
            // with the current graph alone so two large owned graphs are not live together.
            if (next != null && !graphs[i + 1].borrowed) {
                release(i + 1, next!!)
                next = null
                System.gc()
            }
            if (i == 0) {
                val searchStarted = System.nanoTime()
                val reach = current.routeToPortals(startIndex, portals.aIndices, preference, isCancelled, maxExpandedNodes, costMode,
                    includeParentSnapshot = false)
                startSearchNanos += System.nanoTime() - searchStarted
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
                    LongArray(0),
                    IntArray(0),
                )
            } else {
                val sources = seeds.map { PortalSource(it.id, it.index, -1, it.cost, it.distance) }
                val searchStarted = System.nanoTime()
                val reach = current.routeBetweenPortals(sources, portals.aIndices, preference, isCancelled, maxExpandedNodes, costMode)
                middleSearchNanos += System.nanoTime() - searchStarted
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
                val outSeedIds = ArrayList<Long>(); val outSeedIndices = ArrayList<Int>()
                for (p in portals.nodeIds.indices) {
                    val found = foundByIndex[portals.aIndices[p]] ?: continue
                    outSeeds += ChainSeed(portals.nodeIds[p], portals.bIndices[p], found.cost, found.distance)
                    outIds += portals.nodeIds[p]; outIndices += found.index
                    outSeedIds += current.nodeAt(found.originIndex).id
                    outSeedIndices += found.originIndex
                }
                seeds = outSeeds
                snapshots[i] = ChainStageSnapshot(
                    outIds.toLongArray(), outIndices.toIntArray(), outSeedIds.toLongArray(), outSeedIndices.toIntArray(),
                )
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
    val last = timedAcquire(graphs.lastIndex)
    val finalResult: PortalSearchResult
    try {
        val sources = seeds.map { PortalSource(it.id, -1, it.index, it.cost, it.distance) }
        val searchStarted = System.nanoTime()
        finalResult = last.routeFromPortals(sources, goalIndex, preference, isCancelled, maxExpandedNodes, costMode)
        goalSearchNanos += System.nanoTime() - searchStarted
        nodeCounts[nodeCounts.lastIndex] = finalResult.expandedNodes
    } finally { release(graphs.lastIndex, last) }
    stageMs[stageMs.lastIndex] = (System.nanoTime() - finalStarted) / 1_000_000
    if (finalResult.wasCancelled || finalResult.wasTruncated || !finalResult.isReachable) {
        return failed(finalResult.wasCancelled, finalResult.wasTruncated)
    }

    val reconstructionStarted = System.nanoTime()
    val partByStage = arrayOfNulls<CrossRoutePart>(graphs.size)
    run {
        val graph = timedAcquire(graphs.lastIndex)
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
        val seedId = snapshot.seedIds[exitSlot]
        val seedIndex = snapshot.seedIndices[exitSlot]
        val graph = timedAcquire(i)
        try {
            val route = graph.route(seedIndex, snapshot.indices[exitSlot], isCancelled = isCancelled,
                maxExpandedNodes = maxExpandedNodes, preference = preference, costMode = costMode)
            if (route.wasCancelled || route.wasTruncated) return failed(route.wasCancelled, route.wasTruncated)
            if (!route.isReachable) return failed()
            partByStage[i] = makePart(graph, route.nodeIndices)
            selectedId = seedId
        }
        finally { release(i, graph) }
        if (!graphs[i].borrowed) System.gc()
        selectedPortals += selectedId
    }
    val firstSnapshot = snapshots[0] ?: return failed()
    val firstSlot = firstSnapshot.ids.indexOf(selectedId)
    if (firstSlot < 0) return failed()
    val firstTarget = firstSnapshot.indices[firstSlot]
    val firstGraph = timedAcquire(0)
    try {
        val route = firstGraph.route(startIndex, firstTarget, isCancelled = isCancelled,
            maxExpandedNodes = maxExpandedNodes, preference = preference, costMode = costMode)
        if (route.wasCancelled || route.wasTruncated) return failed(route.wasCancelled, route.wasTruncated)
        if (!route.isReachable) return failed()
        partByStage[0] = makePart(firstGraph, route.nodeIndices)
    }
    finally { release(0, firstGraph) }

    val parts = partByStage.map { it ?: CrossRoutePart() }
    val ids = ArrayList<Long>(); val coords = ArrayList<Pair<Double, Double>>()
    for (part in parts) {
        val skip = if (ids.isNotEmpty() && ids.last() == part.nodeIds.firstOrNull()) 1 else 0
        ids += part.nodeIds.drop(skip); coords += part.coordinates.drop(skip)
    }
    selectedPortals.reverse()
    reconstructionNanos += System.nanoTime() - reconstructionStarted
    emitStages()
    return CrossChainResult(ids, coords, finalResult.totalDistanceMeters, finalResult.totalCost, parts, selectedPortals,
        stageSearchMs = stageMs.toList(), stageNodeCounts = nodeCounts.toList())
}

private fun makePart(graph: LazyMappedRoadGraph, indices: List<Int>): CrossRoutePart = CrossRoutePart(
    indices.map { graph.nodeAt(it).id }, indices.map { val n = graph.nodeAt(it); n.latitude to n.longitude }, indices,
)

