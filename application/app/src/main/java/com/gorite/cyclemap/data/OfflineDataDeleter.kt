package com.gorite.cyclemap.data

import com.gorite.cyclemap.routing.CrossPortalsFile
import java.io.File
import java.nio.file.Files

/** 県別ファイルの完全一致リストだけを扱う。サブディレクトリは削除しない。 */
internal object OfflineDataDeleter {
    const val IN_USE_MESSAGE = "使用中です。ほかの県の近くに移動してから消してください。"

    data class Usage(
        val loadedGraphName: String? = null,
        val searchDbName: String? = null,
        val searchDbNames: Set<String> = emptySet(),
        val tileNames: Set<String> = emptySet(),
        val busy: Boolean = false,
    )

    class Plan internal constructor(
        internal val dataDir: File,
        val prefecture: Prefecture,
        val files: List<File>,
        val totalBytes: Long,
    ) {
        val kinds: List<String> get() = files.map { file ->
            when {
                file.name.endsWith(".portals") -> "県またぎの通り口"
                file.name.endsWith(".graph.ch") -> "高速探索"
                file.name.endsWith(".graph.idx") -> "ルート索引"
                file.name.endsWith(".graph") -> "ルート"
                file.name.endsWith(".search.db") || file.name == "search.db" -> "検索"
                file.name.endsWith(".contours.pmtiles") -> "等高線"
                file.name.endsWith(".hillshade.pmtiles") -> "陰影起伏"
                else -> "地図"
            }
        }.distinct()
    }

    data class Result(val failed: List<File> = emptyList(), val refusalReason: String? = null) {
        val isSuccess: Boolean get() = failed.isEmpty() && refusalReason == null
    }

    private fun graphNames(id: String): Set<String> = setOf(
        RoutingGraphSelector.SUPPORTED_FILES.getValue(id),
        "$id.graph", "${id.replaceFirstChar { it.uppercase() }}.graph",
    )

    private fun names(id: String): Set<String> = buildSet {
        graphNames(id).forEach { graph -> add(graph); add("$graph.idx"); add("$graph.ch") }
        add(SearchDbSelector.SUPPORTED_FILES.getValue(id))
        add("$id.search.db")
        add("${id.replaceFirstChar { it.uppercase() }}.search.db")
        val tile = TileSourceSelector.SUPPORTED_PMTILES.getValue(id).removeSuffix(".pmtiles")
        setOf(tile, tile.replaceFirstChar { it.uppercase() }).forEach {
            add("$it.pmtiles"); add("${it}_osm.pmtiles")
        }
        add("$tile.contours.pmtiles"); add("$tile.hillshade.pmtiles")
        // 既存の通り口の命名関数を使い、47県の既知の組み合わせだけを列挙する。
        PrefectureData.ALL.filterNot { it.id == id }.forEach { other ->
            graphNames(id).forEach { a -> graphNames(other.id).forEach { b ->
                add(CrossPortalsFile.fileName(File(a), File(b)))
            } }
        }
    }

    fun plan(dataDir: File, prefecture: Prefecture): Plan {
        require(PrefectureData.ALL.any { it.id == prefecture.id })
        // 候補の名前から File を作ると、大文字小文字を区別しない端末 (Android の /sdcard、macOS など) では
        // 実在する hiroshima.pmtiles を Hiroshima.pmtiles という名前で拾ってしまう。
        // ディレクトリの実際の名前を一覧し、候補と完全に一致するものだけを使う。
        val wanted = names(prefecture.id)
        val files = (dataDir.listFiles() ?: emptyArray()).filter { it.name in wanted }.sortedBy { it.name }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            // 名前が違っても同じファイル (ハードリンクなど) なら 1 つに数える (大きさを二重に数えない)。
            .fold(mutableListOf<File>()) { acc, file ->
                if (acc.none { runCatching { Files.isSameFile(it.toPath(), file.toPath()) }.getOrDefault(false) }) acc.add(file)
                acc
            }
        return Plan(dataDir, prefecture, files, files.sumOf { it.length() })
    }

    fun refusalReason(plan: Plan, usage: Usage): String? = refusalReason(plan.prefecture, usage)

    fun refusalReason(prefecture: Prefecture, usage: Usage): String? {
        val ownNames = names(prefecture.id)
        val activeGraph = usage.loadedGraphName?.let { File(it).name }
        val activeSearch = usage.searchDbName?.let { File(it).name }
        val activeTiles = usage.tileNames.map { File(it).name }
        return if (usage.busy || activeGraph in graphNames(prefecture.id) ||
            activeSearch in ownNames || usage.searchDbNames.any { File(it).name in ownNames } ||
            activeTiles.any { it in ownNames }) IN_USE_MESSAGE else null
    }

    fun delete(plan: Plan, usage: Usage = Usage()): Result {
        refusalReason(plan, usage)?.let { return Result(refusalReason = it) }
        val allowed = names(plan.prefecture.id)
        val failed = plan.files.filter { file ->
            // 確認後の置き換えにも備える。ディレクトリやリンクを辿らない。
            try {
                when {
                    file.parentFile.absoluteFile != plan.dataDir.absoluteFile || file.name !in allowed -> true
                    Files.isSymbolicLink(file.toPath()) -> true
                    !file.exists() -> false
                    !file.isFile -> true
                    else -> !file.delete() && file.exists()
                }
            } catch (_: SecurityException) { true }
        }
        return Result(failed = failed)
    }
}
