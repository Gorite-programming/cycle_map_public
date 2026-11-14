package com.gorite.cyclemap.routing

import java.io.File
import kotlin.system.exitProcess

object ChainHeapBenchMain { @JvmStatic fun main(args: Array<String>) = mainChainHeapBench(args) }

/**
 * 端末のヒープ上限 (512MB) に近い -Xmx で県またぎの CH チェーンを回し、OOM しないかを確かめる。
 * アプリと同じ構成: 先頭の県を「借りたジョブ用グラフ」(標高・道路名つきで常駐、CH 取り付け済み)、ほかの県は都度 open/close (標高などは読まない)。
 * args: graphs(comma) chs(comma) odCount seed [portalsDir] [compareAstar=false]
 * compareAstar=true なら同じ OD を A* のチェーンでも解き、コストと距離を突き合わせる (A* は遅く、ヒープも要る)。
 */
fun mainChainHeapBench(args: Array<String>) {
    val graphFiles = args[0].split(',').map { File(it.trim()) }
    val chFiles = args[1].split(',').map { File(it.trim()) }
    require(graphFiles.size >= 2 && graphFiles.size == chFiles.size)
    val wanted = args.getOrNull(2)?.toInt() ?: 5
    val rnd = java.util.Random(args.getOrNull(3)?.toLong() ?: 20261114L)
    val portalsDir = args.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { File(it) }
    val compareAstar = args.getOrNull(5) == "true"
    val counts = graphFiles.map { f -> LazyMappedRoadGraph.load(f, readRichSections = false).use { it.nodeCount } }

    val job = LazyMappedRoadGraph.load(graphFiles[0]).also { it.attachContractionHierarchy(ContractionHierarchy.open(chFiles[0], graphFiles[0])) }
    fun rt() = Runtime.getRuntime()
    fun usedMb(): Long { System.gc(); return (rt().totalMemory() - rt().freeMemory()) shr 20 }
    println("maxHeap=${rt().maxMemory() shr 20}MB afterJobGraph=${usedMb()}MB nodes=$counts")

    fun chGraphs(withCh: Boolean) = graphFiles.indices.map { i ->
        val f = graphFiles[i]; val c = chFiles[i]
        val portals: (() -> CrossPortals?)? = if (portalsDir == null || i == graphFiles.lastIndex) null else ({ loadCrossPortals(f, graphFiles[i + 1], portalsDir) })
        if (i == 0) ChainGraph(open = { job }, borrowed = true, openIdIndex = { job.sortedNodeIdIndex() }, stageName = f.nameWithoutExtension, portalsToNext = portals)
        else ChainGraph(
            open = { LazyMappedRoadGraph.load(f, readRichSections = false).also { g -> if (withCh) g.attachContractionHierarchy(ContractionHierarchy.open(c, f)) } },
            openIdIndex = { LazyMappedRoadGraph.loadSortedNodeIdIndex(f) }, stageName = f.nameWithoutExtension, portalsToNext = portals,
        )
    }

    var ok = 0; var failed = 0; var peakSample = 0L; var mismatches = 0
    val sampler = Thread {
        try { while (true) { val u = (rt().totalMemory() - rt().freeMemory()) shr 20; if (u > peakSample) peakSample = u; Thread.sleep(25) } } catch (_: InterruptedException) {}
    }.also { it.isDaemon = true; it.start() }
    var attempts = 0
    while (ok + failed < wanted && attempts++ < wanted * 20) {
        val start = rnd.nextInt(counts.first()); val goal = rnd.nextInt(counts.last())
        val t0 = System.nanoTime()
        val r = try { routeAcrossChainCh(chGraphs(true), start, goal) } catch (e: OutOfMemoryError) { println("OOM at OD $start->$goal"); failed++; continue }
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (r == null) { println("CH unavailable (null)"); failed++; continue }
        if (!r.isReachable) { println("OD $start->$goal unreachable ${ms}ms"); continue }
        ok++
        var line = "OD $start->$goal cost=${r.totalCost} dist=${r.totalDistanceMeters} CH=${ms}ms used=${usedMb()}MB peakSampled=${peakSample}MB"
        if (compareAstar) {
            val a = try {
                job.clearWorkspaceCache()
                routeAcrossChain(graphFiles.indices.map { i ->
                    val f = graphFiles[i]
                    if (i == 0) ChainGraph(open = { job }, borrowed = true, openIdIndex = { job.sortedNodeIdIndex() }, stageName = f.nameWithoutExtension)
                    else ChainGraph(open = { LazyMappedRoadGraph.load(f, readRichSections = false) }, openIdIndex = { LazyMappedRoadGraph.loadSortedNodeIdIndex(f) }, stageName = f.nameWithoutExtension)
                }, start, goal)
            } catch (e: OutOfMemoryError) { null }
            if (a == null) line += " A*=OOM"
            else {
                val costOk = kotlin.math.abs(a.totalCost - r.totalCost) <= maxOf(1e-12, kotlin.math.abs(a.totalCost) * 1e-9)
                val distOk = kotlin.math.abs(a.totalDistanceMeters - r.totalDistanceMeters) <= maxOf(1e-6, kotlin.math.abs(a.totalDistanceMeters) * 1e-9)
                line += " A*cost=${a.totalCost} A*dist=${a.totalDistanceMeters} costOk=$costOk distOk=$distOk"
                if (!costOk) mismatches++
            }
        }
        println(line)
        peakSample = 0
    }
    sampler.interrupt()
    println("ok=$ok failed=$failed mismatches=$mismatches")
    job.close()
    if (failed > 0 || mismatches > 0) exitProcess(1)
}
