package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphNode
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.routing.haversineMeters
import com.gorite.cyclemap.routing.pickBenchOdPairs
import java.io.File
import java.util.Locale

/**
 * 経路の「実際の登り量」を標高タイルで測り、方針 (おすすめ/平坦優先/坂道優先) を比べる開発用ツール。
 * 使い方: ./gradlew :osm-importer:benchmarkAscent -Pgraph=<.graph> -Pdem=<dem14 dir> [-Pod=200]
 * 登り量は、経路を約 50m ごとに間引いた標高の増加分の合計 (DEM のノイズを抑えるため)。
 */
fun ascentMeters(elevations: List<Float>, distances: List<Double>, stepMeters: Double = 50.0): Double {
    var ascent = 0.0
    var last = Float.NaN
    var nextAt = 0.0
    for (i in elevations.indices) {
        if (distances[i] < nextAt || elevations[i].isNaN()) continue
        if (!last.isNaN() && elevations[i] > last) ascent += (elevations[i] - last)
        last = elevations[i]
        nextAt = distances[i] + stepMeters
    }
    return ascent
}

fun main(args: Array<String>) {
    val graphFile = File(args[0])
    val dem = DemElevation(File(args[1]))
    val odCount = args.getOrNull(2)?.toInt() ?: 200
    val seed = args.getOrNull(3)?.toLong() ?: 20261108L
    val prefs = listOf(RoutePreference.RECOMMENDED, RoutePreference.FLAT, RoutePreference.HILL_CLIMB)
    LazyMappedRoadGraph.load(graphFile).use { graph ->
        val od = pickBenchOdPairs(graph, odCount, seed)
        val rows = prefs.associateWith { ArrayList<Pair<Double, Double>>() } // (距離, 登り量) 到達した組のみ
        val perOd = ArrayList<Triple<Double, Double, Double>>() // 3 方針とも到達した組の登り量
        for (pair in od) {
            val s = graph.nodeAt(pair.start)
            val g = graph.nodeAt(pair.goal)
            val bounds = LazyMappedRoadGraph.searchBoundsFor(s.latitude, s.longitude, g.latitude, g.longitude)
            val results = prefs.map { p ->
                var r = graph.route(pair.start, pair.goal, bounds = bounds, preference = p)
                if (!r.isReachable) r = graph.route(pair.start, pair.goal, preference = p)
                r
            }
            if (results.any { !it.isReachable }) continue
            val ascents = results.map { r ->
                val nodes = r.nodeIds.indices.map { GraphNode(r.nodeIds[it], r.coordinates[it].first, r.coordinates[it].second) }
                val ele = dem.elevations(nodes)
                val elevations = nodes.map { ele.getValue(it.id) }
                val cum = DoubleArray(nodes.size)
                for (i in 1 until nodes.size) cum[i] = cum[i - 1] + haversineMeters(nodes[i - 1].latitude, nodes[i - 1].longitude, nodes[i].latitude, nodes[i].longitude)
                ascentMeters(elevations, cum.toList())
            }
            prefs.forEachIndexed { i, p -> rows.getValue(p) += results[i].totalDistanceMeters to ascents[i] }
            perOd += Triple(ascents[0], ascents[1], ascents[2])
        }
        fun med(v: List<Double>) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        println("### ${graphFile.name} (3方針とも到達した OD ${perOd.size} 組)")
        println("| 方針 | 中央値 距離m | 中央値 登り量m | 登り量の平均m |")
        println("|---|---:|---:|---:|")
        for (p in prefs) {
            val r = rows.getValue(p)
            println("| ${p.label} | ${"%.0f".format(Locale.US, med(r.map { it.first }))} | ${"%.1f".format(Locale.US, med(r.map { it.second }))} | ${"%.1f".format(Locale.US, r.map { it.second }.average())} |")
        }
        val flatLess = perOd.count { it.second < it.first - 1.0 }
        val flatMore = perOd.count { it.second > it.first + 1.0 }
        println("平坦優先は おすすめ より登り量が 少ない $flatLess 組 / 多い $flatMore 組 / 同程度 ${perOd.size - flatLess - flatMore} 組")
    }
}
