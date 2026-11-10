package com.gorite.cyclemap.routing

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** ./gradlew :routing-core:crossRouteBench -PgraphList=A.graph,B.graph,C.graph -Pod="lat,lon,lat,lon[;...]" */
fun main(args: Array<String>) {
    if (args.size == 3) return legacyMain(args)
    require(args.size == 2) { "Expected graphList od" }
    val files = args[0].split(',').map { File(it.trim()) }
    require(files.size >= 2) { "At least two graphs are required" }
    val ods = args[1].split(';').map { row ->
        val values = row.split(',').map(String::trim).map(String::toDouble)
        require(values.size == 4) { "Each OD must be lat,lon,lat,lon" }
        values
    }
    val stageTotalsMs = linkedMapOf<String, Long>()
    var stageSamples = 0
    for ((index, od) in ods.withIndex()) {
        val graphs = files.map { file -> ChainGraph(
            open = { LazyMappedRoadGraph.load(file) },
            openIdIndex = { LazyMappedRoadGraph.loadSortedNodeIdIndex(file) },
            stageName = file.nameWithoutExtension,
        ) }
        val start = nearestNode(files.first(), od[0], od[1])
        val goal = nearestNode(files.last(), od[2], od[3])
        System.gc()
        val before = usedHeapBytes()
        val sampler = CrossChainHeapSampler().also { it.start() }
        val routeStarted = System.nanoTime()
        val result = try {
            routeAcrossChain(graphs, start, goal, stageTimingSink = { line ->
                println(line)
                val name = line.substringAfter("name=").substringBefore(' ')
                val ms = line.substringAfter("ms=").toLongOrNull() ?: return@routeAcrossChain
                stageTotalsMs[name] = (stageTotalsMs[name] ?: 0L) + ms
            }).also { stageSamples++ }
        } finally { sampler.finish() }
        val elapsedMs = (System.nanoTime() - routeStarted) / 1_000_000.0
        val after = usedHeapBytes()
        println(String.format(Locale.US,
            "OD%d reachable=%s elapsedMs=%.1f distanceM=%.1f cost=%.3f portals=%s nodeSha256=%s stageMs=%s stageExpanded=%s graphNodes=%s partNodes=%s heapBeforeMB=%.1f heapPeakSampleMB=%.1f heapAfterMB=%.1f heapDeltaMB=%.1f cancelled=%s truncated=%s",
            index + 1, result.isReachable, elapsedMs, result.totalDistanceMeters, result.totalCost,
            result.portalIds.joinToString(","), nodeIdsSha256(result.nodeIds), result.stageSearchMs.joinToString(","), result.stageNodeCounts.joinToString(","),
            files.map(::graphNodeCount).joinToString(","), result.parts.map { it.nodeIds.size }.joinToString(","),
            before / 1048576.0, sampler.peakBytes / 1048576.0, after / 1048576.0,
            (after - before) / 1048576.0, result.wasCancelled, result.wasTruncated))
    }
    if (stageSamples > 0) {
        println("CHAIN_STAGE_SUMMARY stage=average_ms samples=$stageSamples")
        for ((name, total) in stageTotalsMs) println("$name\t${"%.1f".format(Locale.US, total.toDouble() / stageSamples)}")
    }
}

/** Preserves the original two-graph bench format and output for existing scripts. */
private fun legacyMain(args: Array<String>) {
    val fileA = File(args[0]); val fileB = File(args[1])
    val ods = args[2].split(';').map { row ->
        val values = row.split(',').map(String::trim).map(String::toDouble)
        require(values.size == 4) { "Each OD must be lat,lon,lat,lon" }
        values
    }
    LazyMappedRoadGraph.load(fileA).use { a -> LazyMappedRoadGraph.load(fileB).use { b ->
        val portalStarted = System.nanoTime()
        val portals = portalsBetween(a, b)
        val portalMs = (System.nanoTime() - portalStarted) / 1e6
        println("portals=${portals.size} portalMatchingMs=${fmt(portalMs)}")
        for ((index, od) in ods.withIndex()) {
            val start = a.nearestNodeIndex(od[0], od[1]); val goal = b.nearestNodeIndex(od[2], od[3])
            val tA = System.nanoTime()
            val reach = a.routeToPortals(start, portals.aIndices)
            val aMs = (System.nanoTime() - tA) / 1e6
            if (!reach.wasCancelled && !reach.wasTruncated) {
                val costs = reach.portals.associateBy { it.aIndex }
                val sources = portals.nodeIds.indices.mapNotNull { k -> costs[portals.aIndices[k]]?.let { PortalSource(it.nodeId, it.aIndex, portals.bIndices[k], it.cost, it.distanceMeters) } }
                val tB = System.nanoTime()
                val routed = b.routeFromPortals(sources, goal)
                val bMs = (System.nanoTime() - tB) / 1e6
                val total = aMs + bMs + portalMs
                val portalNode = routed.seedPortalId?.let { id -> (0 until portals.size).firstOrNull { portals.nodeIds[it] == id }?.let { b.nodeAt(portals.bIndices[it]) } }
                println(String.format(Locale.US,
                    "OD%d A(start=%d,expanded=%d,ms=%.1f) B(goal=%d,expanded=%d,ms=%.1f) totalMs=%.1f reachable=%s distanceM=%.1f cost=%.3f portalId=%s portalCoord=%s aNodes=%d bNodes=%d cancelled=%s truncated=%s",
                    index + 1, start, reach.expandedNodes, aMs, goal, routed.expandedNodes, bMs, total, routed.isReachable,
                    routed.totalDistanceMeters, routed.totalCost, routed.seedPortalId ?: "-",
                    portalNode?.let { "${it.latitude},${it.longitude}" } ?: "-", a.nodeCount, b.nodeCount, routed.wasCancelled, routed.wasTruncated))
            } else println("OD${index + 1} A expanded=${reach.expandedNodes} ms=${fmt(aMs)} cancelled=${reach.wasCancelled} truncated=${reach.wasTruncated}")
        }
    } }
}

private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
private fun nearestNode(file: File, lat: Double, lon: Double): Int = LazyMappedRoadGraph.load(file).use { it.nearestNodeIndex(lat, lon) }
private fun graphNodeCount(file: File): Int = java.io.RandomAccessFile(file, "r").use { input ->
    val header = ByteArray(input.readUnsignedShort())
    input.readFully(header)
    input.readInt()
}
private fun fmt(value: Double) = String.format(Locale.US, "%.1f", value)

private fun nodeIdsSha256(nodeIds: List<Long>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (id in nodeIds) for (shift in 56 downTo 0 step 8) digest.update((id ushr shift).toByte())
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private class CrossChainHeapSampler {
    @Volatile var peakBytes: Long = usedHeapBytes()
        private set
    @Volatile private var running = true
    private val worker = Thread({
        while (running) {
            peakBytes = maxOf(peakBytes, usedHeapBytes())
            try { Thread.sleep(5) } catch (_: InterruptedException) { break }
        }
        peakBytes = maxOf(peakBytes, usedHeapBytes())
    }, "cross-route-heap-sampler").apply { isDaemon = true }
    fun start() = worker.start()
    fun finish() { running = false; worker.join() }
}
