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
    // 段を終えたら、借りたグラフでも CH の作業配列 (県単位で数十 MB) を先に手放す。次の県を開くときに同時に載る量を減らす。
    fun release(i: Int, graph: LazyMappedRoadGraph) {
        graph.attachedHierarchy?.releaseWorkspaces()
        if (!graphs[i].borrowed) graphs[i].close(graph)
    }
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
    /** 前の段で読んだ「この段の県」の ID 索引。現在の県を開き直して再ソートするのを避ける。 */
    var carriedIdIndex: SortedNodeIdIndex? = null
    /** 今の seeds の番号 (次の県のノード番号) が通り口ファイル由来か。次の県を開いたときに確かめる。 */
    var seedsFromFile = false
    fun verifiedSeeds(graph: LazyMappedRoadGraph, current: List<ChSeed>): List<ChSeed> {
        if (!seedsFromFile || portalSideMatches(graph, current.size, { current[it].index }, { current[it].id })) return current
        // ファイルの b 側がこの graph と合わない (識別をすり抜けた古いファイル)。ID から番号を引き直す。
        stageTimingSink?.invoke("CHAIN_PORTALS repair=b_side count=${current.size}")
        val index = graph.sortedNodeIdIndex()
        return current.mapNotNull { s -> index.binarySearch(s.id).let { k -> if (k < 0) null else ChSeed(s.id, index.nodeIndex(k), s.cost) } }
    }
    for (i in 0 until graphs.lastIndex) {
        if (isCancelled?.invoke() == true) return failed(true)
        val started = System.nanoTime()
        val current = timedAcquire(i)
        var next: LazyMappedRoadGraph? = null
        try {
            val ch = current.attachedHierarchy ?: return null
            if (i > 0) seeds = verifiedSeeds(current, seeds)
            val hasIdOnlyPath = graphs[i + 1].openIdIndex != null
            val portalStarted = System.nanoTime()
            // 事前計算した通り口があれば、隣県の ID 索引も現在の県の索引も作らない。使えなければ従来の照合。
            val filePortals = graphs[i].portalsToNext?.let { usablePortals(current, it) }
            var nextIdIndex: SortedNodeIdIndex? = null
            val portals = filePortals ?: run {
                nextIdIndex = graphs[i + 1].openIdIndex?.invoke()
                nextIdIndex?.let { portalsBetween(carriedIdIndex ?: current.sortedNodeIdIndex(), it) }
                    ?: run {
                        next = timedAcquire(i + 1)
                        if (next!!.attachedHierarchy == null) return null
                        portalsBetween(current, next!!)
                    }
            }
            portalMatchNanos += System.nanoTime() - portalStarted
            stageTimingSink?.invoke("CHAIN_PORTALS stage=$i source=${if (filePortals != null) "file" else "match"} count=${portals.size}")
            carriedIdIndex = nextIdIndex
            seedsFromFile = filePortals != null
            if (filePortals == null && hasIdOnlyPath && !graphs[i + 1].borrowed) System.gc()
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
        seeds = verifiedSeeds(last, seeds)
        if (seeds.isEmpty()) return failed()
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

/** 読み込んだ通り口の a 側が [graph] のものか確かめる。使えない (無い・例外・0 件・graph と合わない) なら null。 */
private fun usablePortals(graph: LazyMappedRoadGraph, load: () -> CrossPortals?): CrossPortals? {
    val portals = try { load() } catch (e: Exception) { null } ?: return null
    if (portals.size == 0 || portals.aIndices.size != portals.size || portals.bIndices.size != portals.size) return null
    return portals.takeIf { p -> portalSideMatches(graph, p.size, { p.aIndices[it] }, { p.nodeIds[it] }) }
}

/**
 * 番号がすべて範囲内で、抜き取った最大 [PORTAL_ID_SAMPLES] 件の ID が graph のノードと一致するか。
 * 別の graph 向けのファイルならほぼ全件ずれるので抜き取りで足り、ノード表を広く読まずに済む (本体の破損は CRC で検出済み)。
 */
private fun portalSideMatches(graph: LazyMappedRoadGraph, count: Int, indexAt: (Int) -> Int, idAt: (Int) -> Long): Boolean {
    val n = graph.nodeCount
    for (p in 0 until count) if (indexAt(p) !in 0 until n) return false
    val step = maxOf(1, count / PORTAL_ID_SAMPLES)
    var p = 0
    while (p < count) { if (graph.nodeAt(indexAt(p)).id != idAt(p)) return false; p += step }
    return count == 0 || graph.nodeAt(indexAt(count - 1)).id == idAt(count - 1)
}

private const val PORTAL_ID_SAMPLES = 64

private fun chMakePart(graph: LazyMappedRoadGraph, indices: IntArray): CrossRoutePart = CrossRoutePart(
    indices.map { graph.nodeAt(it).id }, indices.map { val n = graph.nodeAt(it); n.latitude to n.longitude }, indices.toList(),
)
