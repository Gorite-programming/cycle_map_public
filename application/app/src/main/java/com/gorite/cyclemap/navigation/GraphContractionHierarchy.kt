package com.gorite.cyclemap.navigation

import android.util.Log
import com.gorite.cyclemap.routing.ChLoadResult
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.attachContractionHierarchyFrom
import java.io.File

/**
 * 読み込んだ graph に `<graph>.ch` (Contraction Hierarchies) を取り付ける。無い・識別不一致・失敗のときは
 * ログを 1 行出して何もしない (A* のまま)。読み込みの実体は routing-core の loadContractionHierarchy に集約している。
 *
 * [warmUp] が true のときは、取り付けに成功したら裏のスレッドで試し探索を数回走らせる。実機では CH の最初の 1 回だけが
 * 約 1 秒かかり (2 回目以降は約 20ms)、一度きりの初期化とコードの初回実行のコストなので、ユーザーが経路を引く前に済ませておく。
 * 失敗しても何も起きない (最初の経路が少し遅くなるだけ)。
 */
fun loadContractionHierarchy(graph: LazyMappedRoadGraph, graphFile: File, warmUp: Boolean = false): ChLoadResult {
    val result = graph.attachContractionHierarchyFrom(graphFile) { Log.i("CycleMapGraph", it) }
    if (warmUp && result.hierarchy != null) {
        Thread({
            val started = System.nanoTime()
            try {
                val n = graph.nodeCount
                if (n > 16) {
                    graph.route(n / 7, n * 5 / 7)
                    graph.route(n / 3, n / 2)
                    graph.route(n * 6 / 7, n / 5)
                }
                Log.i("CycleMapGraph", "CH_WARMUP name=${graphFile.name} ms=${(System.nanoTime() - started) / 1_000_000}")
            } catch (t: Throwable) {
                Log.w("CycleMapGraph", "CH_WARMUP name=${graphFile.name} failed=$t")
            }
        }, "ch-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
    return result
}
