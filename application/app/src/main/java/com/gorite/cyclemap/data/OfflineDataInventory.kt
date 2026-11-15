package com.gorite.cyclemap.data

import com.gorite.cyclemap.routing.findContractionHierarchyFile
import com.gorite.cyclemap.ui.mapview.terrainFilesFor
import java.io.File
import java.util.Locale

/** アプリと同じ探索規則で県のファイルを集計する。県境ファイルは含めない。 */
internal fun prefectureDataStatus(dataDir: File, id: String, name: String): PrefectureDataStatus {
    val graphName = RoutingGraphSelector.SUPPORTED_FILES.getValue(id)
    val graph = RoutingGraphSelector.resolveGraphFiles(dataDir, graphName)
    val search = SearchDbSelector.resolveDbFile(dataDir, SearchDbSelector.SUPPORTED_FILES.getValue(id))
    val tileName = TileSourceSelector.SUPPORTED_PMTILES.getValue(id)
    val tiles = TileSourceSelector.resolveTileFile(dataDir, tileName)
    // 地図未配置でも、先に転送された地形ファイルを表示する。
    val terrain = terrainFilesFor((tiles ?: File(dataDir, tileName)).absolutePath)
    val hierarchy = findContractionHierarchyFile(graph?.first ?: File(dataDir, graphName))
    val files = listOfNotNull(graph?.first, graph?.second, search, tiles,
        terrain.hillshade, terrain.contours, hierarchy).distinctBy { it.absolutePath }
    return PrefectureDataStatus(
        id = id,
        name = name,
        hasRouting = graph != null,
        hasSearch = search != null,
        hasVectorMap = tiles != null,
        hasTerrain = !terrain.isEmpty,
        hasFastRouting = hierarchy != null,
        totalBytes = files.sumOf { it.length() },
    )
}

/** 実容量の表示。タイル保存の概算容量とは分けて扱う。 */
internal fun formatOfflineDataSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
