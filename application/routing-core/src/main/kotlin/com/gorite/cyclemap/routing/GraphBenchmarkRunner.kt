package com.gorite.cyclemap.routing

import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

/** 実グラフでの経路品質ベンチの 1 条件 (既存の探索フラグだけを切り替える。コストモデル本体は変えない)。 */
data class BenchVariant(
    val name: String,
    val preference: RoutePreference = RoutePreference.RECOMMENDED,
    val arterialPenaltyMultiplier: Double = 1.0,
)

data class BenchOd(val start: Int, val goal: Int, val straightMeters: Double)

data class BenchSample(
    val reachable: Boolean,
    val usedUnboundedRetry: Boolean,
    val elapsedMillis: Double,
    val expandedNodes: Int,
    val distanceMeters: Double,
    val cost: Double,
)

data class BenchVariantSummary(
    val variant: BenchVariant,
    val samples: List<BenchSample>,
) {
    private val ok get() = samples.filter { it.reachable }
    val reachableRate: Double get() = if (samples.isEmpty()) 0.0 else ok.size.toDouble() / samples.size
    fun median(f: (BenchSample) -> Double): Double = ok.map(f).sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
    fun p95(f: (BenchSample) -> Double): Double = ok.map(f).sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * 0.95).roundToInt()] }
}

/** 固定シードで、直線距離が [minMeters, maxMeters] に入るノード組を [count] 組選ぶ。 */
fun pickBenchOdPairs(
    graph: LazyMappedRoadGraph,
    count: Int,
    seed: Long,
    minMeters: Double = 3_000.0,
    maxMeters: Double = 40_000.0,
): List<BenchOd> {
    val random = java.util.Random(seed)
    val pairs = ArrayList<BenchOd>(count)
    var guard = 0
    while (pairs.size < count && guard++ < count * 2000) {
        val a = random.nextInt(graph.nodeCount)
        val b = random.nextInt(graph.nodeCount)
        if (a == b) continue
        val na = graph.nodeAt(a)
        val nb = graph.nodeAt(b)
        val straight = haversineMeters(na.latitude, na.longitude, nb.latitude, nb.longitude)
        if (straight in minMeters..maxMeters) pairs += BenchOd(a, b, straight)
    }
    return pairs
}

/** アプリと同じ流れ: 探索範囲つきで探索し、到達不能なら範囲なしで再試行する。 */
fun runBenchVariant(graph: LazyMappedRoadGraph, od: List<BenchOd>, variant: BenchVariant): BenchVariantSummary {
    val samples = od.map { pair ->
        val s = graph.nodeAt(pair.start)
        val g = graph.nodeAt(pair.goal)
        val bounds = LazyMappedRoadGraph.searchBoundsFor(s.latitude, s.longitude, g.latitude, g.longitude)
        var retried = false
        var expanded = 0
        lateinit var result: MappedRouteResult
        val nanos = measureNanoTime {
            result = graph.route(
                pair.start, pair.goal, bounds = bounds,
                arterialPenaltyMultiplier = variant.arterialPenaltyMultiplier,
                preference = variant.preference,
            )
            expanded += graph.lastExpandedNodes
            if (!result.isReachable) {
                retried = true
                result = graph.route(
                    pair.start, pair.goal, bounds = null,
                    arterialPenaltyMultiplier = variant.arterialPenaltyMultiplier,
                    preference = variant.preference,
                )
                expanded += graph.lastExpandedNodes
            }
        }
        BenchSample(result.isReachable, retried, nanos / 1_000_000.0, expanded, result.totalDistanceMeters, result.totalCost)
    }
    return BenchVariantSummary(variant, samples)
}

/** 先頭の条件 (基準) との比較表 (Markdown)。 */
fun formatBenchReport(graphName: String, odCount: Int, summaries: List<BenchVariantSummary>): String = buildString {
    val base = summaries.first()
    fun f(v: Double, fmt: String = "%.1f") = if (v.isNaN()) "-" else fmt.format(Locale.US, v)
    appendLine("### $graphName (OD $odCount 組)")
    appendLine()
    appendLine("| 条件 | 到達率 | 範囲外再試行 | 中央値 時間ms | p95 時間ms | 中央値 展開ノード | 中央値 経路距離 | 基準比 距離増加(中央値) |")
    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
    for (s in summaries) {
        // 同じ OD で両方到達した組だけで距離を比べる
        val deltas = s.samples.indices.mapNotNull { i ->
            val a = base.samples[i]; val b = s.samples[i]
            if (a.reachable && b.reachable && a.distanceMeters > 0) (b.distanceMeters / a.distanceMeters - 1.0) * 100.0 else null
        }.sorted()
        val deltaMedian = if (deltas.isEmpty()) Double.NaN else deltas[deltas.size / 2]
        appendLine(
            "| ${s.variant.name} | ${f(s.reachableRate * 100, "%.1f")}% | ${s.samples.count { it.usedUnboundedRetry }} | " +
                "${f(s.median { it.elapsedMillis })} | ${f(s.p95 { it.elapsedMillis })} | ${f(s.median { it.expandedNodes.toDouble() }, "%.0f")} | " +
                "${f(s.median { it.distanceMeters })}m | ${f(deltaMedian, "%+.2f")}% |",
        )
    }
}

/**
 * 実グラフのベンチ。使い方: gradlew :routing-core:benchmarkRealGraph -Pgraph=<.graph のパス> [-Pod=200] [-Pseed=20261108]
 * 結果の Markdown を標準出力へ出す。
 */
fun main(args: Array<String>) {
    val graphFile = File(args.getOrNull(0) ?: error("graph file path required"))
    val odCount = args.getOrNull(1)?.toInt() ?: 200
    val seed = args.getOrNull(2)?.toLong() ?: 20261108L
    LazyMappedRoadGraph.load(graphFile).use { graph ->
        val od = pickBenchOdPairs(graph, odCount, seed)
        val variants = listOf(
            BenchVariant("基準 (おすすめ)"),
            BenchVariant("平坦優先", preference = RoutePreference.FLAT),
            BenchVariant("坂道優先", preference = RoutePreference.HILL_CLIMB),
            BenchVariant("幹線ペナルティ 1.25", arterialPenaltyMultiplier = 1.25),
            BenchVariant("幹線ペナルティ 1.5", arterialPenaltyMultiplier = 1.5),
        )
        // ウォームアップ (JIT) を基準条件で 20 組だけ回す
        runBenchVariant(graph, od.take(20), variants.first())
        val summaries = variants.map { runBenchVariant(graph, od, it) }
        println(formatBenchReport(graphFile.name, od.size, summaries))
    }
}
