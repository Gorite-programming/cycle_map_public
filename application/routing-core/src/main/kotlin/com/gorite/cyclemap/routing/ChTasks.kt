package com.gorite.cyclemap.routing

import java.io.File
import kotlin.math.abs
import kotlin.system.measureNanoTime

object ChBuildMain { @JvmStatic fun main(args: Array<String>) = mainBuildCh(args) }
object ChBenchmarkMain { @JvmStatic fun main(args: Array<String>) = mainBenchmarkCh(args) }

fun mainBuildCh(args: Array<String>) {
    val input = File(args.getOrNull(0) ?: error("graph path required")); val out = File(args.getOrNull(1) ?: error("output path required"))
    LazyMappedRoadGraph.load(input).use { graph ->
        val r = ContractionHierarchyBuilder().build(graph, GraphFingerprint.fromFile(input)); out.parentFile?.mkdirs(); r.hierarchy.write(out)
        println("CH build ms=${r.elapsedMillis} nodes=${graph.nodeCount} original_edges=${r.inputEdgeCount} shortcuts=${r.shortcutCount} stored_arcs=${r.inputEdgeCount + r.shortcutCount} upward_arcs=${r.hierarchy.edgeCount} bytes=${out.length()} graph_bytes=${input.length()} size_ratio=${out.length().toDouble() / input.length()}")
        println("witness_max_settled=${ContractionHierarchyBuilder.DEFAULT_MAX_WITNESS_SETTLED} witness_max_cost=${ContractionHierarchyBuilder.DEFAULT_MAX_WITNESS_COST}")
    }
}

fun mainBenchmarkCh(args: Array<String>) {
    val graphFile = File(args.getOrNull(0) ?: error("graph path required")); val chFile = File(args.getOrNull(1) ?: error("CH path required"))
    val count = args.getOrNull(2)?.toInt() ?: 1000; val seed = args.getOrNull(3)?.toLong() ?: 20261108L
    LazyMappedRoadGraph.load(graphFile).use { graph ->
        val ch = ContractionHierarchy.read(chFile, graphFile); require(ch.nodeCount == graph.nodeCount)
        val od = pickBenchOdPairs(graph, count, seed)
        // Warm both paths before collecting timings.
        for (p in od.take(20)) { graph.route(p.start, p.goal); ch.route(p.start, p.goal, graph) }
        val aTimes = DoubleArray(od.size); val cTimes = DoubleArray(od.size); var mismatch = 0; var distMismatch = 0
        val report = ArrayList<String>()
        for ((i, p) in od.withIndex()) {
            lateinit var a: MappedRouteResult; lateinit var c: ChRouteResult
            aTimes[i] = measureNanoTime { a = graph.route(p.start, p.goal, preference = RoutePreference.RECOMMENDED, costMode = RouteCostMode.BICYCLE) } / 1e6
            cTimes[i] = measureNanoTime { c = ch.route(p.start, p.goal, graph) } / 1e6
            val ok = a.isReachable == c.isReachable
            val costOk = !a.isReachable || abs(a.totalCost - c.totalCost) <= maxOf(1e-12, abs(a.totalCost) * 1e-9)
            if (!ok || !costOk) { mismatch++; if (report.size < 20) report += "OD ${p.start}->${p.goal} A*=${a.totalCost}/${a.totalDistanceMeters}/${a.isReachable} CH=${c.totalCost}/${c.totalDistance}/${c.isReachable}" }
            if (a.isReachable && c.isReachable && abs(a.totalDistanceMeters - c.totalDistance) > maxOf(1e-6, abs(a.totalDistanceMeters) * 1e-9)) distMismatch++
        }
        fun stats(x: DoubleArray): String { val s=x.sorted(); val med=if (s.size % 2 == 0) (s[s.size/2-1] + s[s.size/2]) / 2.0 else s[s.size/2]; return "avg=${x.average()} median=$med p95=${s[((s.size-1)*.95).toInt()]}" }
        println("OD=${od.size} mismatch=$mismatch distance_mismatch=$distMismatch A* ${stats(aTimes)} ms CH ${stats(cTimes)} ms")
        report.forEach(::println)
        if (mismatch > 0) throw IllegalStateException("CH comparison failed: $mismatch mismatches")
    }
}

