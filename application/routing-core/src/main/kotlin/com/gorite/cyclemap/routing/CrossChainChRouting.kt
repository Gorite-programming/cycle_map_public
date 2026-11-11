package com.gorite.cyclemap.routing

private class ChStageSnapshot(
    val ids: LongArray,
    /** Exit portal node index in this stage's graph. */
    val indices: IntArray,
    /** Entry portal id/index (in this stage's graph) that gives the cheapest way to the matching exit. Empty for stage 0. */
    val seedIds: LongArray,
    val seedIndices: IntArray,
)

private class ChSeed(val id: Long, val index: Int, val cost: Double)

/**
 * Contraction Hierarchies version of [routeAcrossChain] (default preference, bicycle cost only). Works for two or more graphs.
 * Returns null — never a failure result — when a graph has no attached hierarchy, so the caller can fall back to the A* chain.
 * Unreachable and cancelled queries return an unreachable [CrossChainResult] like [routeAcrossChain].
 * Owned graphs are expected to have their hierarchy attached by `ChainGraph.open`; the router never attaches one.
 */
fun routeAcrossChainCh(
    graphs: List<ChainGraph>,
    startIndex: Int,
    goalIndex: Int,
    isCancelled: (() -> Boolean)? = null,
    stageTimingSink: ((String) -> Unit)? = null,
): CrossChainResult? {
    require(graphs.size >= 2) { "A CH chain requires at least two graphs" }
    val stageMs = MutableList(graphs.size) { 0L }
    val nodeCounts = MutableList(graphs.size) { 0 }
    val snapshots = arrayOfNulls<ChStageSnapshot>(graphs.size - 1)
    val graphOpenNanos = LongArray(graphs.size)
    var portalMatchNanos = 0L; var startNanos = 0L; var middleNanos = 0L; var goalNanos = 0L; var reconstructionNanos = 0L

    fun timedAcquire(i: Int): LazyMappedRoadGraph {
        val started = System.nanoTime()
        try { return graphs[i].open().also { if (graphs[i].borrowed) it.clearWorkspaceCache() } }
        finally { graphOpenNanos[i] += System.nanoTime() - started }
    }
    fun release(i: Int, graph: LazyMappedRoadGraph) { if (!graphs[i].borrowed) graphs[i].close(graph) }
    fun emitStages() {
        val sink = stageTimingSink ?: return
        fun emit(name: String, nanos: Long) { sink("CHAIN_STAGE name=$name ms=${nanos / 1_000_000}") }
        emit("portal_match", portalMatchNanos)
        graphOpenNanos.forEachIndexed { i, nanos -> emit("graph_open_${graphs[i].stageName ?: i}", nanos) }
        emit("ch_start_search", startNanos)
        emit("ch_middle", middleNanos)
        emit("ch_goal", goalNanos)
        emit("reconstruction", reconstructionNanos)
    }
    fun failed(cancelled: Boolean = false): CrossChainResult {
        emitStages()
        return CrossChainResult(
            emptyList(), emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, emptyList(), emptyList(),
            cancelled, false, stageMs.toList(), nodeCounts.toList(),
        )
    }

    var seeds: List<ChSeed> = emptyList()
    for (i in 0 until graphs.lastIndex) {
        if (isCancelled?.invoke() == true) return failed(true)
        val started = System.nanoTime()
        val current = timedAcquire(i)
        var next: LazyMappedRoadGraph? = null
        try {
            val ch = current.attachedHierarchy ?: return null
            val hasIdOnlyPath = graphs[i + 1].openIdIndex != null
            val portalStarted = System.nanoTime()
            val portals = graphs[i + 1].openIdIndex?.invoke()?.let { portalsBetween(current, it) }
                ?: run {
                    next = timedAcquire(i + 1)
                    if (next!!.attachedHierarchy == null) return null
                    portalsBetween(current, next!!)
                }
            portalMatchNanos += System.nanoTime() - portalStarted
            if (hasIdOnlyPath && !graphs[i + 1].borrowed) System.gc()
            if (portals.size == 0) { stageMs[i] = (System.nanoTime() - started) / 1_000_000; return failed() }
            if (next != null && !graphs[i + 1].borrowed) { release(i + 1, next!!); next = null; System.gc() }

            val searchStarted = System.nanoTime()
            val sources: IntArray; val costs: DoubleArray
            if (i == 0) { sources = intArrayOf(startIndex); costs = doubleArrayOf(0.0) }
            else { sources = IntArray(seeds.size) { seeds[it].index }; costs = DoubleArray(seeds.size) { seeds[it].cost } }
            val many = ch.searchMany(sources, costs, portals.aIndices)
            if (i == 0) startNanos += System.nanoTime() - searchStarted else middleNanos += System.nanoTime() - searchStarted
            stageTimingSink?.invoke("CHAIN_DETAIL stage=$i ${ch.lastManyStats}")
            val outSeeds = ArrayList<ChSeed>()
            val ids = ArrayList<Long>(); val indices = ArrayList<Int>(); val seedIds = ArrayList<Long>(); val seedIndices = ArrayList<Int>()
            for (p in 0 until portals.size) {
                val c = many.bestCost[p]; if (c.isInfinite()) continue
                outSeeds += ChSeed(portals.nodeIds[p], portals.bIndices[p], c)
                ids += portals.nodeIds[p]; indices += portals.aIndices[p]
                if (i > 0) { val s = seeds[many.bestSource[p]]; seedIds += s.id; seedIndices += s.index }
            }
            nodeCounts[i] = outSeeds.size
            stageMs[i] = (System.nanoTime() - started) / 1_000_000
            if (outSeeds.isEmpty()) return failed()
            seeds = outSeeds
            snapshots[i] = ChStageSnapshot(ids.toLongArray(), indices.toIntArray(), seedIds.toLongArray(), seedIndices.toIntArray())
        } finally {
            try { next?.let { release(i + 1, it) } }
            finally { release(i, current) }
        }
        if (!graphs[i].borrowed || !graphs[i + 1].borrowed) System.gc()
    }

    if (isCancelled?.invoke() == true) return failed(true)
    val partByStage = arrayOfNulls<CrossRoutePart>(graphs.size)
    val distances = DoubleArray(graphs.size)
    var totalCost: Double
    var selectedId: Long
    val finalStarted = System.nanoTime()
    val last = timedAcquire(graphs.lastIndex)
    try {
        val ch = last.attachedHierarchy ?: return null
        val searchStarted = System.nanoTime()
        val many = ch.searchMany(IntArray(seeds.size) { seeds[it].index }, DoubleArray(seeds.size) { seeds[it].cost }, intArrayOf(goalIndex))
        goalNanos += System.nanoTime() - searchStarted
        stageMs[stageMs.lastIndex] = (System.nanoTime() - finalStarted) / 1_000_000
        if (many.bestCost[0].isInfinite()) return failed()
        totalCost = many.bestCost[0]
        val seed = seeds[many.bestSource[0]]; selectedId = seed.id
        val reconstructionStarted = System.nanoTime()
        val route = ch.route(seed.index, goalIndex, last)
        reconstructionNanos += System.nanoTime() - reconstructionStarted
        if (!route.isReachable) return failed()
        partByStage[graphs.lastIndex] = chMakePart(last, route.nodeIndices); distances[graphs.lastIndex] = route.totalDistance
        nodeCounts[nodeCounts.lastIndex] = 1
    } finally { release(graphs.lastIndex, last) }
    if (!graphs.last().borrowed) System.gc()

    val reconstructionStarted = System.nanoTime()
    val selectedPortals = ArrayList<Long>()
    selectedPortals += selectedId
    for (i in graphs.lastIndex - 1 downTo 0) {
        if (isCancelled?.invoke() == true) return failed(true)
        val snapshot = snapshots[i] ?: return failed()
        val exitSlot = snapshot.ids.indexOf(selectedId)
        if (exitSlot < 0) return failed()
        val fromIndex = if (i == 0) startIndex else snapshot.seedIndices[exitSlot]
        val nextId = if (i == 0) 0L else snapshot.seedIds[exitSlot]
        val tOpen = System.nanoTime()
        val graph = timedAcquire(i)
        val tOpened = System.nanoTime()
        var tRoute = 0L; var tPart = 0L; var pathLen = 0
        try {
            val ch = graph.attachedHierarchy ?: return null
            val a = System.nanoTime()
            val route = ch.route(fromIndex, snapshot.indices[exitSlot], graph)
            val b = System.nanoTime(); tRoute = b - a
            if (!route.isReachable) return failed()
            pathLen = route.nodeIndices.size
            partByStage[i] = chMakePart(graph, route.nodeIndices); distances[i] = route.totalDistance
            tPart = System.nanoTime() - b
        } finally { release(i, graph) }
        val g0 = System.nanoTime()
        if (!graphs[i].borrowed) System.gc()
        stageTimingSink?.invoke("CHAIN_DETAIL recon stage=$i openMs=${(tOpened - tOpen) / 1_000_000} routeMs=${tRoute / 1_000_000} pathNodes=$pathLen partMs=${tPart / 1_000_000} ${graph.attachedHierarchy?.lastRouteStats} gcMs=${(System.nanoTime() - g0) / 1_000_000}")
        if (i > 0) { selectedId = nextId; selectedPortals += selectedId }
    }
    selectedPortals.reverse()
    val parts = partByStage.map { it ?: CrossRoutePart() }
    val ids = ArrayList<Long>(); val coords = ArrayList<Pair<Double, Double>>()
    for (part in parts) {
        val skip = if (ids.isNotEmpty() && ids.last() == part.nodeIds.firstOrNull()) 1 else 0
        ids += part.nodeIds.drop(skip); coords += part.coordinates.drop(skip)
    }
    reconstructionNanos += System.nanoTime() - reconstructionStarted
    emitStages()
    return CrossChainResult(ids, coords, distances.sum(), totalCost, parts, selectedPortals,
        stageSearchMs = stageMs.toList(), stageNodeCounts = nodeCounts.toList())
}

private fun chMakePart(graph: LazyMappedRoadGraph, indices: IntArray): CrossRoutePart = CrossRoutePart(
    indices.map { graph.nodeAt(it).id }, indices.map { val n = graph.nodeAt(it); n.latitude to n.longitude }, indices.toList(),
)
