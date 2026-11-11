package com.gorite.cyclemap.routing

import java.io.File
import kotlin.math.abs
import kotlin.system.measureNanoTime

object AttachBenchMain { @JvmStatic fun main(args: Array<String>) = mainAttachBench(args) }

/** attach 版 LazyMappedRoadGraph.route と A* (CH を外した同一 graph) の結果を OD ごとに突き合わせる。 */
fun mainAttachBench(args: Array<String>) {
    val graphFile = File(args.getOrNull(0) ?: error("graph path required"))
    val chFile = File(args.getOrNull(1) ?: error("CH path required"))
    val count = args.getOrNull(2)?.toInt() ?: 1000
    val seed = args.getOrNull(3)?.toLong() ?: 20261108L
    LazyMappedRoadGraph.load(graphFile).use { graph ->
        // CH は graph の隣に無くてもよい (チェックは graph 識別のみ)。隣に置いた場合の探索は attachContractionHierarchyFrom が担う。
        val t0 = System.nanoTime()
        val ch = ContractionHierarchy.read(chFile, graphFile)
        require(ch.nodeCount == graph.nodeCount)
        println("CH read ms=${(System.nanoTime() - t0) / 1_000_000}")
        val od = pickBenchOdPairs(graph, count, seed)
        var costMismatch = 0; var distMismatch = 0; var reachMismatch = 0; var pathDiff = 0; var chUsed = 0; var aNanos = 0L; var cNanos = 0L
        val report = ArrayList<String>()
        for (p in od) {
            graph.attachContractionHierarchy(null)
            lateinit var a: MappedRouteResult; lateinit var c: MappedRouteResult
            aNanos += measureNanoTime { a = graph.route(p.start, p.goal) }
            check(graph.lastEngine == "astar")
            graph.attachContractionHierarchy(ch)
            cNanos += measureNanoTime { c = graph.route(p.start, p.goal) }
            if (graph.lastEngine == "ch") chUsed++
            if (a.isReachable != c.isReachable) { reachMismatch++; if (report.size < 20) report += "OD ${p.start}->${p.goal} reachability A*=${a.isReachable} CH=${c.isReachable}"; continue }
            if (!a.isReachable) continue
            if (abs(a.totalCost - c.totalCost) > maxOf(1e-12, abs(a.totalCost) * 1e-9)) { costMismatch++; if (report.size < 20) report += "OD ${p.start}->${p.goal} cost A*=${a.totalCost} CH=${c.totalCost}" }
            if (abs(a.totalDistanceMeters - c.totalDistanceMeters) > maxOf(1e-6, abs(a.totalDistanceMeters) * 1e-9)) distMismatch++
            if (a.nodeIndices != c.nodeIndices) pathDiff++
        }
        println("OD=${od.size} chUsed=$chUsed reachMismatch=$reachMismatch costMismatch=$costMismatch distMismatch=$distMismatch pathNodeSeqDiffer=$pathDiff A*avgMs=${aNanos / 1e6 / od.size} CHavgMs=${cNanos / 1e6 / od.size}")
        report.forEach(::println)
        if (reachMismatch + costMismatch + distMismatch > 0) throw IllegalStateException("attach comparison failed")
    }
}
