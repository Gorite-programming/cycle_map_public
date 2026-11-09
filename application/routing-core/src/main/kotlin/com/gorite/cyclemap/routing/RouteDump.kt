package com.gorite.cyclemap.routing

import java.io.File
import java.util.Locale

/**
 * 経路探索の結果を固定の OD で書き出す (探索の実装を変える前後で、結果が変わらないことを確かめる)。
 * 使い方: gradlew :routing-core:routeDump -Pgraph=<.graph> -Pout=<出力> [-Pod=200]
 * 各行: OD番号 条件 到達 コスト 距離 展開ノード数。最後に条件ごとの合計時間を出す。
 */
fun main(args: Array<String>) {
    val graphFile = File(args[0])
    val out = File(args[1])
    val odCount = args.getOrNull(2)?.toInt() ?: 200
    LazyMappedRoadGraph.load(graphFile).use { graph ->
        val od = pickBenchOdPairs(graph, odCount, 20261108L)
        data class Variant(val name: String, val run: (BenchOd) -> MappedRouteResult)
        val variants = listOf(
            Variant("recommended") { graph.route(it.start, it.goal) },
            Variant("flat") { graph.route(it.start, it.goal, preference = RoutePreference.FLAT) },
            Variant("hill") { graph.route(it.start, it.goal, preference = RoutePreference.HILL_CLIMB) },
            // アプリの代替ルート (RealRoutePlanner) と同じ条件
            Variant("alternative") { graph.route(it.start, it.goal, heuristicMultiplier = 1.15, arterialPenaltyMultiplier = 1.30) },
            Variant("bounded") {
                val s = graph.nodeAt(it.start); val g = graph.nodeAt(it.goal)
                graph.route(it.start, it.goal, bounds = LazyMappedRoadGraph.searchBoundsFor(s.latitude, s.longitude, g.latitude, g.longitude))
            },
        )
        // JIT のウォームアップ
        od.take(20).forEach { variants.first().run(it) }
        val lines = StringBuilder()
        val times = LinkedHashMap<String, Double>()
        for (v in variants) {
            var totalNanos = 0L
            od.forEachIndexed { i, pair ->
                val t0 = System.nanoTime()
                val r = v.run(pair)
                totalNanos += System.nanoTime() - t0
                lines.append(String.format(Locale.US, "%d %s %s %.6f %.6f %d%n", i, v.name, r.isReachable, r.totalCost, r.totalDistanceMeters, graph.lastExpandedNodes))
            }
            times[v.name] = totalNanos / 1e6
        }
        out.writeText(lines.toString())
        // 経路の後処理 (アプリの獲得標高の計算と同じ呼び出し) の時間
        val sample = od.take(30).map { graph.route(it.start, it.goal) }.filter { it.isReachable }
        var gradeNanos = 0L; var eleNanos = 0L; var points = 0
        for (r in sample) {
            points += r.coordinates.size
            gradeNanos += kotlin.system.measureNanoTime { graph.segmentGradesAlong(r.coordinates) }
            eleNanos += kotlin.system.measureNanoTime { graph.nodeElevationsAlong(r.coordinates) }
        }
        var idxNanos = 0L; var mismatches = 0
        for (r in sample) {
            var g2: List<RouteSegmentGrade> = emptyList(); var e2: List<Double?> = emptyList()
            idxNanos += kotlin.system.measureNanoTime {
                g2 = graph.segmentGradesAlongIndices(r.nodeIndices); e2 = graph.nodeElevationsAt(r.nodeIndices)
            }
            if (g2 != graph.segmentGradesAlong(r.coordinates) || e2 != graph.nodeElevationsAlong(r.coordinates)) mismatches++
        }
        println(String.format(Locale.US, "postprocess routes=%d points=%d segmentGradesAlong %.0f ms, nodeElevationsAlong %.0f ms, index版 %.0f ms, 不一致 %d 経路",
            sample.size, points, gradeNanos / 1e6, eleNanos / 1e6, idxNanos / 1e6, mismatches))
        times.forEach { (k, ms) -> println(String.format(Locale.US, "%-12s total %.0f ms (%.1f ms/OD)", k, ms, ms / od.size)) }
    }
}
