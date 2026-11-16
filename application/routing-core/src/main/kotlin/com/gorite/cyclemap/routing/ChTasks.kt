package com.gorite.cyclemap.routing

import java.io.File
import kotlin.math.abs
import kotlin.system.measureNanoTime

object ChBuildMain { @JvmStatic fun main(args: Array<String>) = mainBuildCh(args) }
object ChBenchmarkMain { @JvmStatic fun main(args: Array<String>) = mainBenchmarkCh(args) }
object ChPortalsMain { @JvmStatic fun main(args: Array<String>) = mainBuildPortals(args) }

/**
 * 通り口ファイルの生成。
 * - `<A.graph> <B.graph> <out.portals>`: 1 組。
 * - `--all <outDir> <graph>...`: 渡した graph のすべての組 (各 graph の ID 索引は 1 回だけ読む)。ファイル名は [CrossPortalsFile.fileName]。
 * 通り口が 0 件の組はファイルを作らない (出力先に古いファイルがあれば消す)。1 組ごとに `PORTALS pair=... count=...` を出す。
 */
fun mainBuildPortals(args: Array<String>) {
    val all = args.firstOrNull() == "--all"
    val graphs = (if (all) args.drop(2) else args.take(2)).map { File(it) }
    require(graphs.size >= 2 && (all || args.size == 3)) { "usage: <A.graph> <B.graph> <out.portals> | --all <outDir> <graph> <graph>..." }
    graphs.forEach { require(it.isFile) { "graph not found: $it" } }
    val indexes = HashMap<File, SortedNodeIdIndex>(); val fingerprints = HashMap<File, GraphFingerprint>()
    for (i in graphs.indices) for (j in i + 1 until graphs.size) {
        // ファイルの A 側は名前の辞書順で先の graph
        val (a, b) = if (CrossPortalsFile.isCanonicalOrder(graphs[i], graphs[j])) graphs[i] to graphs[j] else graphs[j] to graphs[i]
        val out = if (all) File(args[1], CrossPortalsFile.fileName(a, b)) else File(args[2])
        val started = System.nanoTime()
        val portals = portalsBetween(indexes.getOrPut(a) { LazyMappedRoadGraph.loadSortedNodeIdIndex(a) }, indexes.getOrPut(b) { LazyMappedRoadGraph.loadSortedNodeIdIndex(b) })
        if (portals.size == 0) out.delete()
        else {
            val fa = fingerprints.getOrPut(a) { GraphFingerprint.fromFile(a) }; val fb = fingerprints.getOrPut(b) { GraphFingerprint.fromFile(b) }
            CrossPortalsFile.write(out, portals, fa, fb)
            val back = CrossPortalsFile.read(out, fa, fb)
            check(back != null && back.nodeIds.contentEquals(portals.nodeIds) && back.aIndices.contentEquals(portals.aIndices) && back.bIndices.contentEquals(portals.bIndices)) { "Read-back mismatch: $out" }
        }
        println("PORTALS pair=${a.nameWithoutExtension}__${b.nameWithoutExtension} count=${portals.size} ms=${(System.nanoTime() - started) / 1_000_000}" +
            if (portals.size == 0) " (no file)" else " bytes=${out.length()} out=$out")
    }
}

fun mainBuildCh(args: Array<String>) {
    val input = File(args.getOrNull(0) ?: error("graph path required")); val out = File(args.getOrNull(1) ?: error("output path required"))
    LazyMappedRoadGraph.load(input).use { graph ->
        val verbose = System.getProperty("ch.progress") != null
        val r = ContractionHierarchyBuilder(maxWitnessSettled = System.getProperty("ch.witnessSettled")?.toInt() ?: ContractionHierarchyBuilder.DEFAULT_MAX_WITNESS_SETTLED).build(graph, GraphFingerprint.fromFile(input), if (verbose) { m -> System.err.println("CH_PROGRESS $m") } else null); out.parentFile?.mkdirs(); r.hierarchy.write(out)
        println("CH build ms=${r.elapsedMillis} nodes=${graph.nodeCount} original_edges=${r.inputEdgeCount} shortcuts=${r.shortcutCount} stored_arcs=${r.inputEdgeCount + r.shortcutCount} upward_arcs=${r.hierarchy.edgeCount} bytes=${out.length()} graph_bytes=${input.length()} size_ratio=${out.length().toDouble() / input.length()}")
        println("witness_max_settled=${ContractionHierarchyBuilder.DEFAULT_MAX_WITNESS_SETTLED} witness_max_cost=${ContractionHierarchyBuilder.DEFAULT_MAX_WITNESS_COST}")
    }
}

fun mainBenchmarkCh(args: Array<String>) {
    val graphFile = File(args.getOrNull(0) ?: error("graph path required")); val chFile = File(args.getOrNull(1) ?: error("CH path required"))
    val count = args.getOrNull(2)?.toInt() ?: 1000; val seed = args.getOrNull(3)?.toLong() ?: 20261108L
    // mmap: "bare" = mmap only, no A* (heap measurement); "false" = heap read only, "true" = heap read + mmap open (results compared), "only" = mmap open only (compared against A*)
    val mmap = args.getOrNull(4) ?: "false"
    fun stats(x: DoubleArray): String { val s=x.sorted(); val med=if (s.size % 2 == 0) (s[s.size/2-1] + s[s.size/2]) / 2.0 else s[s.size/2]; return "avg=${x.average()} median=$med p95=${s[((s.size-1)*.95).toInt()]}" }
    LazyMappedRoadGraph.load(graphFile).use { graph ->
        val ch = if (mmap == "only" || mmap == "bare") null else ContractionHierarchy.read(chFile, graphFile)
        val chm = if (mmap == "false") null else ContractionHierarchy.open(chFile, graphFile)
        require((ch ?: chm!!).nodeCount == graph.nodeCount)
        val primary = ch ?: chm!!
        val od = pickBenchOdPairs(graph, count, seed)
        // Warm A* and the heap CH before collecting timings (mmap is deliberately left cold for the first pass).
        if (mmap != "bare") for (p in od.take(20)) { graph.route(p.start, p.goal); ch?.route(p.start, p.goal, graph) }
        if (mmap == "bare") {
            // CH query only (no A*, no heap CH): isolates the heap cost of the mmap hierarchy. Not compared; use "only" for that.
            val t = DoubleArray(od.size); var reach = 0
            for ((i, p) in od.withIndex()) t[i] = measureNanoTime { if (chm!!.route(p.start, p.goal, graph).isReachable) reach++ } / 1e6
            System.gc(); val rt = Runtime.getRuntime()
            println("OD=${od.size} reachable=$reach CH mmap bare ${stats(t)} ms heap_used_mb=${(rt.totalMemory() - rt.freeMemory()) / 1048576} max_heap_mb=${rt.maxMemory() / 1048576}")
            chm!!.close(); return
        }
        val aTimes = DoubleArray(od.size); val cTimes = DoubleArray(od.size); val mCold = DoubleArray(od.size); val mWarm = DoubleArray(od.size)
        var mismatch = 0; var distMismatch = 0; var mmapMismatch = 0
        val report = ArrayList<String>()
        for ((i, p) in od.withIndex()) {
            lateinit var a: MappedRouteResult; lateinit var c: ChRouteResult
            aTimes[i] = measureNanoTime { a = graph.route(p.start, p.goal, preference = RoutePreference.RECOMMENDED, costMode = RouteCostMode.BICYCLE) } / 1e6
            if (ch != null) cTimes[i] = measureNanoTime { c = ch.route(p.start, p.goal, graph) } / 1e6
            if (chm != null) {
                lateinit var m: ChRouteResult
                mCold[i] = measureNanoTime { m = chm.route(p.start, p.goal, graph) } / 1e6
                if (ch == null) c = m
                else if (m.isReachable != c.isReachable || m.totalCost != c.totalCost || m.totalDistance != c.totalDistance || !m.nodeIndices.contentEquals(c.nodeIndices)) {
                    mmapMismatch++; if (report.size < 20) report += "OD ${p.start}->${p.goal} heapCH vs mmapCH differ"
                }
            }
            val ok = a.isReachable == c.isReachable
            val costOk = !a.isReachable || abs(a.totalCost - c.totalCost) <= maxOf(1e-12, abs(a.totalCost) * 1e-9)
            if (!ok || !costOk) { mismatch++; if (report.size < 20) report += "OD ${p.start}->${p.goal} A*=${a.totalCost}/${a.totalDistanceMeters}/${a.isReachable} CH=${c.totalCost}/${c.totalDistance}/${c.isReachable}" }
            if (a.isReachable && c.isReachable && abs(a.totalDistanceMeters - c.totalDistance) > maxOf(1e-6, abs(a.totalDistanceMeters) * 1e-9)) distMismatch++
        }
        if (chm != null) for ((i, p) in od.withIndex()) mWarm[i] = measureNanoTime { chm.route(p.start, p.goal, graph) } / 1e6
        println("OD=${od.size} mismatch=$mismatch distance_mismatch=$distMismatch mmap_vs_heap_mismatch=$mmapMismatch A* ${stats(aTimes)} ms")
        if (ch != null) println("CH heap ${stats(cTimes)} ms")
        if (chm != null) { println("CH mmap cold(first pass) ${stats(mCold)} ms"); println("CH mmap warm(second pass) ${stats(mWarm)} ms") }
        val rt = Runtime.getRuntime(); println("heap_used_mb=${(rt.totalMemory() - rt.freeMemory()) / 1048576} max_heap_mb=${rt.maxMemory() / 1048576}")
        report.forEach(::println)
        chm?.close(); ch?.close()
        if (mismatch > 0 || mmapMismatch > 0) throw IllegalStateException("CH comparison failed: $mismatch vs A*, $mmapMismatch heap-vs-mmap")
    }
}
