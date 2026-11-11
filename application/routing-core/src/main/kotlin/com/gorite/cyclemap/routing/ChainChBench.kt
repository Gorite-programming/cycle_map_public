package com.gorite.cyclemap.routing

import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

object ChainChBenchMain { @JvmStatic fun main(args: Array<String>) = mainBenchmarkChainCh(args) }

/** args: graphs(comma) chs(comma) odCount seed. Compares the A* chain (routeAcrossGraphs for 2 graphs) against routeAcrossChainCh. */
fun mainBenchmarkChainCh(args: Array<String>) {
    val graphFiles = args[0].split(',').map { File(it.trim()) }
    val chFiles = args[1].split(',').map { File(it.trim()) }
    require(graphFiles.size >= 2 && graphFiles.size == chFiles.size)
    val wanted = args.getOrNull(2)?.toInt() ?: 20
    val seed = args.getOrNull(3)?.toLong() ?: 20261108L
    val counts = graphFiles.map { f -> LazyMappedRoadGraph.load(f).use { it.nodeCount } }
    val rnd = java.util.Random(seed)

    fun astarGraphs() = graphFiles.map { f -> ChainGraph({ LazyMappedRoadGraph.load(f) }, openIdIndex = { LazyMappedRoadGraph.loadSortedNodeIdIndex(f) }, stageName = f.nameWithoutExtension) }
    fun chGraphs() = graphFiles.indices.map { i ->
        val f = graphFiles[i]; val c = chFiles[i]
        ChainGraph(
            open = { LazyMappedRoadGraph.load(f).also { g -> g.attachContractionHierarchy(ContractionHierarchy.open(c, f)) } },
            close = { g -> g.attachedHierarchy?.close(); g.close() },
            openIdIndex = { LazyMappedRoadGraph.loadSortedNodeIdIndex(f) }, stageName = f.nameWithoutExtension,
        )
    }
    fun astar(start: Int, goal: Int): CrossChainResult {
        val gs = astarGraphs()
        if (gs.size > 2) return routeAcrossChain(gs, start, goal)
        val a = gs[0].open(); val b = gs[1].open()
        try {
            val r = routeAcrossGraphs(a, start, b, goal, portalsBetween(a, b))
            return CrossChainResult(r.nodeIds, r.coordinates, r.totalDistanceMeters, r.totalCost, listOf(r.aPart, r.bPart), listOfNotNull(r.portalId), r.wasCancelled, r.wasTruncated)
        } finally { a.close(); b.close() }
    }

    val aTimes = ArrayList<Double>(); val cTimes = ArrayList<Double>(); val cNoOpen = ArrayList<Double>()
    val stageSums = linkedMapOf<String, Double>()
    var compared = 0; var mismatches = 0; var distTies = 0; var unreachableBoth = 0; var unreachableChecked = 0; var attempts = 0
    val report = ArrayList<String>()
    // one untimed warm-up so JIT/page-cache state does not dominate the first sample
    run { val s = rnd.nextInt(counts.first()); val g = rnd.nextInt(counts.last()); routeAcrossChainCh(chGraphs(), s, g) }
    while (compared < wanted && attempts++ < wanted * 20) {
        val start = rnd.nextInt(counts.first()); val goal = rnd.nextInt(counts.last())
        val stages = linkedMapOf<String, Double>()
        val t0 = System.nanoTime()
        val c = routeAcrossChainCh(chGraphs(), start, goal) { line ->
            stages[line.substringAfter("name=").substringBefore(' ')] = line.substringAfter("ms=").toDouble()
        } ?: error("CH missing for a graph (null result)")
        val cMs = (System.nanoTime() - t0) / 1e6
        if (!c.isReachable && unreachableChecked >= 3) continue // skip the slow exhaustive A* confirmation after 3 samples
        val t1 = System.nanoTime(); val a = astar(start, goal); val aMs = (System.nanoTime() - t1) / 1e6
        if (!a.isReachable && !c.isReachable) { unreachableBoth++; unreachableChecked++; continue }
        if (a.isReachable != c.isReachable) {
            mismatches++; if (report.size < 20) report += "OD $start->$goal reachability A*=${a.isReachable} CH=${c.isReachable}"; compared++; continue
        }
        compared++
        aTimes += aMs; cTimes += cMs
        cNoOpen += cMs - stages.filterKeys { it.startsWith("graph_open_") || it == "portal_match" }.values.sum()
        stages.forEach { (k, v) -> stageSums[k] = (stageSums[k] ?: 0.0) + v }
        val costOk = abs(a.totalCost - c.totalCost) <= maxOf(1e-12, abs(a.totalCost) * 1e-9)
        val distOk = abs(a.totalDistanceMeters - c.totalDistanceMeters) <= maxOf(1e-6, abs(a.totalDistanceMeters) * 1e-9)
        if (!costOk) { mismatches++; if (report.size < 20) report += "OD $start->$goal cost A*=${a.totalCost} CH=${c.totalCost}" }
        else if (!distOk) { distTies++; if (report.size < 20) report += "OD $start->$goal (tie? cost equal) distance A*=${a.totalDistanceMeters} CH=${c.totalDistanceMeters}" }
        println("OD $start->$goal cost=${c.totalCost} dist=${c.totalDistanceMeters} A*=%.0fms CH=%.0fms CH_excl_open=%.0fms".format(aMs, cMs, cNoOpen.last()))
    }
    fun st(x: List<Double>): String { if (x.isEmpty()) return "n/a"; val s = x.sorted(); val med = if (s.size % 2 == 0) (s[s.size / 2 - 1] + s[s.size / 2]) / 2 else s[s.size / 2]; return "avg=%.1f median=%.1f p95=%.1f".format(x.average(), med, s[((s.size - 1) * .95).toInt()]) }
    println("graphs=${graphFiles.joinToString(",") { it.nameWithoutExtension }} compared_reachable=${aTimes.size} unreachable_both_checked=$unreachableBoth mismatches=$mismatches distance_differences_with_equal_cost=$distTies")
    println("A* chain ms: ${st(aTimes)}")
    println("CH chain ms (incl. open): ${st(cTimes)}")
    println("CH chain ms (excl. graph_open+portal_match): ${st(cNoOpen)}")
    if (aTimes.isNotEmpty()) { println("CH per-stage average ms:"); stageSums.forEach { (k, v) -> println("  $k=%.1f".format(v / aTimes.size)) } }
    report.forEach(::println)
    if (mismatches > 0) exitProcess(1)
}
