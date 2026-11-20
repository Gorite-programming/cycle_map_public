package com.gorite.cyclemap.data

import com.gorite.cyclemap.navigation.PREFECTURE_ADJACENCY_EDGES
import com.gorite.cyclemap.routing.CrossPortalsFile
import java.io.File

internal data class ReadinessPoint(val latitude: Double, val longitude: Double)

/** データ dir からの相対パスと容量だけ。診断中にファイル本体を開かない。 */
internal data class ReadinessFile(val path: String, val bytes: Long)
internal enum class ReadinessState(val label: String) {
    AVAILABLE("使える"), MISSING("未導入"), EMPTY("壊れている疑い（0 バイト）"),
}
internal data class ReadinessItem(val label: String, val state: ReadinessState, val optional: Boolean = false)
internal data class PrefectureReadiness(val name: String, val items: List<ReadinessItem>)
internal data class OfflineReadiness(
    val prefectures: List<PrefectureReadiness>,
    val portals: List<ReadinessItem>,
    val unknownPointCount: Int,
) {
    val isReady: Boolean get() = prefectures.isNotEmpty() && unknownPointCount == 0 &&
        prefectures.all { pref -> pref.items.filterNot { it.optional }.all { it.state == ReadinessState.AVAILABLE } }
}

/** IO スレッドで呼ぶ。探索対象は既存 selector と同じ直下および tiles/。 */
internal fun readinessFileInventory(dataDir: File): List<ReadinessFile> =
    listOf(dataDir, File(dataDir, "tiles")).flatMap { dir ->
        dir.listFiles().orEmpty().filter { it.isFile }.map {
            ReadinessFile(it.relativeTo(dataDir).invariantSeparatorsPath, it.length())
        }
    }

/** 純粋診断。県判定・県名・通り口名は既存実装を使う。内容の整合性までは検証しない。 */
internal fun diagnoseOfflineReadiness(points: List<ReadinessPoint>, files: List<ReadinessFile>): OfflineReadiness {
    val inventory = files.associate { it.path to it.bytes }
    fun state(paths: List<String>, skipEmpty: Boolean = false): ReadinessState {
        val sizes = paths.mapNotNull { inventory[it] }
        val size = (if (skipEmpty) sizes.firstOrNull { it > 0 } else sizes.firstOrNull()) ?: sizes.firstOrNull()
        return when { size == null -> ReadinessState.MISSING; size <= 0 -> ReadinessState.EMPTY; else -> ReadinessState.AVAILABLE }
    }
    val names = points.map { if (it.latitude.isFinite() && it.longitude.isFinite()) findPrefectureName(it.latitude, it.longitude) else null }
    val prefs = names.filterNotNull().distinct().mapNotNull { name -> PrefectureData.ALL.firstOrNull { it.name == name } }
    val graphNames = mutableMapOf<String, String>()
    val statuses = prefs.map { pref ->
        val graph = RoutingGraphSelector.SUPPORTED_FILES.getValue(pref.id)
        val alternate = if (graph.first().isUpperCase()) graph.replaceFirstChar { it.lowercase() } else graph.replaceFirstChar { it.uppercase() }
        val graphCandidates = listOf(graph, alternate)
        val resolvedGraph = graphCandidates.firstOrNull { inventory.containsKey(it) && inventory.containsKey("$it.idx") }
        graphNames[pref.name] = resolvedGraph ?: graph
        val routing = resolvedGraph?.let { if (inventory.getValue(it) <= 0 || inventory.getValue("$it.idx") <= 0) ReadinessState.EMPTY else ReadinessState.AVAILABLE }
            ?: if (graphCandidates.any { inventory[it] == 0L || inventory["$it.idx"] == 0L }) ReadinessState.EMPTY else ReadinessState.MISSING
        val search = SearchDbSelector.SUPPORTED_FILES.getValue(pref.id)
        val searchCandidates = if (search == "search.db") listOf(search, "Yamaguchi.search.db", "yamaguchi.search.db")
            else listOf(search, search.replaceFirstChar { it.lowercase() })
        val tile = TileSourceSelector.SUPPORTED_PMTILES.getValue(pref.id)
        val stems = listOf(pref.id, pref.id.replaceFirstChar { it.uppercase() })
        val tilePaths = (listOf(tile, "tiles/$tile") + stems.flatMap { listOf("$it.pmtiles", "${it}_osm.pmtiles") }
            .flatMap { listOf(it, "tiles/$it") }).distinct()
        val resolvedTile = tilePaths.firstOrNull { (inventory[it] ?: 0) > 0 } ?: tile
        val tileDir = resolvedTile.substringBeforeLast('/', "")
        fun terrain(kind: String): ReadinessState {
            val name = "${pref.id}.$kind.pmtiles"
            return state(if (tileDir.isEmpty()) listOf(name, "tiles/$name") else listOf("$tileDir/$name", "$tileDir/tiles/$name"), true)
        }
        val chGraph = resolvedGraph ?: graph
        PrefectureReadiness(pref.name, listOf(
            ReadinessItem("地図データ", state(tilePaths, true)),
            ReadinessItem("検索データ", state(searchCandidates)),
            ReadinessItem("経路データ", routing),
            ReadinessItem("高速化", state(listOf("$chGraph.ch", "${chGraph.lowercase()}.ch")), true),
            ReadinessItem("等高線", terrain("contours"), true),
            ReadinessItem("陰影", terrain("hillshade"), true),
        ))
    }
    val selected = prefs.map { it.name }.toSet()
    val portals = PREFECTURE_ADJACENCY_EDGES.filter { it.first in selected && it.second in selected }.map { (a, b) ->
        val path = CrossPortalsFile.fileName(File(graphNames.getValue(a)), File(graphNames.getValue(b)))
        val fallback = inventory.keys.firstOrNull { it.equals(path, ignoreCase = true) }
        ReadinessItem("$a・$b: 通り口", state(listOfNotNull(path, fallback)), true)
    }
    return OfflineReadiness(statuses, portals, names.count { it == null })
}
