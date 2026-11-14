package com.gorite.cyclemap.data

import java.io.File

/** 1 県分の端末内データの有無。アプリからは取得できず、PC で作ったデータを端末へ転送して使う。 */
data class PrefectureDataStatus(
    val id: String,
    val name: String,
    val hasRouting: Boolean,
    val hasSearch: Boolean,
    val hasVectorMap: Boolean,
) {
    val isComplete: Boolean get() = hasRouting && hasSearch && hasVectorMap
    val isEmpty: Boolean get() = !hasRouting && !hasSearch && !hasVectorMap
}

/** ルート探索・施設検索・ベクター地図の 3 種が、対応県ごとにそろっているかを調べる (ファイル確認のみで軽い)。 */
internal fun offlineDataStatus(dataDir: File): List<PrefectureDataStatus> {
    val order = RoutingGraphSelector.SUPPORTED_FILES.keys
    return order.mapNotNull { id ->
        val pref = PrefectureData.ALL.firstOrNull { it.id == id } ?: return@mapNotNull null
        val graph = RoutingGraphSelector.SUPPORTED_FILES[id]
            ?.let { RoutingGraphSelector.resolveGraphFiles(dataDir, it) } != null
        val search = SearchDbSelector.SUPPORTED_FILES[id]
            ?.let { SearchDbSelector.resolveDbFile(dataDir, it) } != null
        val tiles = TileSourceSelector.SUPPORTED_PMTILES[id]
            ?.let { TileSourceSelector.resolveTileFile(dataDir, it) } != null
        PrefectureDataStatus(id, pref.name, graph, search, tiles)
    }
}
