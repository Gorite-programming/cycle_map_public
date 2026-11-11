package com.gorite.cyclemap.routing

import java.io.File

/** `.graph.ch` の読み込み結果。A* のままにする場合は [hierarchy] が null。 */
data class ChLoadResult(val status: Status, val hierarchy: ContractionHierarchy?, val file: File?, val millis: Long, val detail: String? = null) {
    enum class Status(val label: String) { ATTACHED("attached"), MISSING("missing"), MISMATCH("mismatch"), ERROR("error") }
}

/**
 * `<graph>.ch` を探す。graph のファイル名にそのまま `.ch` を付けた名前、無ければ小文字名 (`hiroshima.graph.ch`)。
 */
fun findContractionHierarchyFile(graphFile: File): File? {
    val dir = graphFile.absoluteFile.parentFile ?: return null
    val exact = File(dir, graphFile.name + ".ch")
    if (exact.isFile) return exact
    val lower = File(dir, graphFile.name.lowercase() + ".ch")
    if (lower.isFile) return lower
    return null
}

/**
 * CH 読み込みを 1 か所に閉じ込めた関数 (mmap 版 `ContractionHierarchy.open` への差し替えは [reader] の既定値を変えるだけ)。
 * 例外は投げない。無い / 識別不一致 / 失敗はすべて結果の status で返し、呼び出し側は A* のまま動く。
 */
fun loadContractionHierarchy(
    graphFile: File,
    expectedNodeCount: Int = -1,
    reader: (chFile: File, graphFile: File) -> ContractionHierarchy = { ch, graph -> ContractionHierarchy.open(ch, graph) },
): ChLoadResult {
    val started = System.nanoTime()
    fun ms() = (System.nanoTime() - started) / 1_000_000
    val chFile = findContractionHierarchyFile(graphFile) ?: return ChLoadResult(ChLoadResult.Status.MISSING, null, null, ms())
    return try {
        val ch = reader(chFile, graphFile)
        if (expectedNodeCount >= 0 && ch.nodeCount != expectedNodeCount) {
            ChLoadResult(ChLoadResult.Status.MISMATCH, null, chFile, ms(), "nodeCount ch=${ch.nodeCount} graph=$expectedNodeCount")
        } else {
            ChLoadResult(ChLoadResult.Status.ATTACHED, ch, chFile, ms())
        }
    } catch (e: IllegalArgumentException) {
        if (e.message?.contains("mismatch") == true) ChLoadResult(ChLoadResult.Status.MISMATCH, null, chFile, ms(), e.message)
        else ChLoadResult(ChLoadResult.Status.ERROR, null, chFile, ms(), e.toString())
    } catch (e: Exception) {
        ChLoadResult(ChLoadResult.Status.ERROR, null, chFile, ms(), e.toString())
    } catch (e: OutOfMemoryError) {
        ChLoadResult(ChLoadResult.Status.ERROR, null, chFile, ms(), e.toString())
    }
}

/** [loadContractionHierarchy] の結果を graph に取り付ける。ログ 1 行 (`CH_ATTACH ...`) を [log] へ渡す。 */
fun LazyMappedRoadGraph.attachContractionHierarchyFrom(graphFile: File, log: (String) -> Unit = {}): ChLoadResult {
    val r = loadContractionHierarchy(graphFile, nodeCount)
    r.hierarchy?.let { attachContractionHierarchy(it) }
    log("CH_ATTACH name=${graphFile.name} result=${r.status.label} ms=${r.millis}" + (r.detail?.let { " detail=$it" } ?: ""))
    return r
}
