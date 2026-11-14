package com.gorite.cyclemap.data

import java.io.File

/** routing用県グラフの選択結果。 */
sealed interface RoutingGraphSelection {
    /** 対応グラフあり。fileName は CycleMap データ dir 直下の実ファイル名。 */
    data class Available(
        val prefectureId: String,
        val prefectureName: String,
        val fileName: String,
    ) : RoutingGraphSelection

    /** 対応グラフなし (未対応県・県判定不能)。他県グラフの使い回しは禁止のため呼び出し側は失敗させる。 */
    data class Unavailable(val prefectureName: String?) : RoutingGraphSelection
}

/**
 * 現在地→県→routing用グラフの選択 (純粋関数・JVMテスト可能)。
 * 県判定は既存 [findPrefectureName]、県定義は既存 [PrefectureData.ALL] を再利用する。
 */
object RoutingGraphSelector {
    /**
     * 県グラフの名前 (県ID → ファイル名)。47 県すべてを `<Romaji>.graph` (パイプラインの出力名) で引く。
     * 山口だけは既配備の `yamaguchi.graph` を先頭候補にする (`resolveGraphFiles` が大文字小文字を補う)。
     * ここに載っていても端末にファイルがあるとは限らない。実在は呼び出し側が `resolveGraphFiles` で確かめる。
     */
    internal val SUPPORTED_FILES: Map<String, String> = PrefectureData.ALL.associate { pref ->
        pref.id to if (pref.id == "yamaguchi") "yamaguchi.graph" else "${pref.id.replaceFirstChar { it.uppercase() }}.graph"
    }

    /** 県名 (例: 岡山県) → グラフのファイル名。県またぎ経路の計画で使う。 */
    fun graphFileNameForPrefectureName(prefectureName: String): String? =
        PrefectureData.ALL.firstOrNull { it.name == prefectureName }?.let { SUPPORTED_FILES[it.id] }

    fun select(latitude: Double, longitude: Double): RoutingGraphSelection {
        val name = findPrefectureName(latitude, longitude)
        val pref = PrefectureData.ALL.firstOrNull { it.name == name }
        val file = pref?.let { SUPPORTED_FILES[it.id] }
        return if (pref != null && file != null) {
            RoutingGraphSelection.Available(pref.id, pref.name, file)
        } else {
            RoutingGraphSelection.Unavailable(name)
        }
    }

    /** 対応する .graph.idx のファイル名。 */
    fun indexFileName(graphFileName: String): String = "$graphFileName.idx"

    /**
     * 実在するグラフファイルとインデックスファイルのペアを解決する。
     * パイプライン生成物 (Yamaguchi.graph) と既存配備 (yamaguchi.graph) の
     * 大文字小文字の違いをフォールバック解決する (BUG-65)。
     */
    fun resolveGraphFiles(dataDir: File, fileName: String): Pair<File, File>? {
        val primaryGraph = File(dataDir, fileName)
        val primaryIdx = File(dataDir, indexFileName(fileName))
        if (primaryGraph.isFile && primaryIdx.isFile) return primaryGraph to primaryIdx

        val altName = if (fileName.startsWith("yamaguchi", ignoreCase = true)) {
            if (fileName.startsWith("yamaguchi")) "Yamaguchi.graph" else "yamaguchi.graph"
        } else {
            val capitalized = fileName.replaceFirstChar { it.uppercase() }
            if (capitalized == fileName) fileName.replaceFirstChar { it.lowercase() } else capitalized
        }
        val altGraph = File(dataDir, altName)
        val altIdx = File(dataDir, indexFileName(altName))
        if (altGraph.isFile && altIdx.isFile) return altGraph to altIdx

        return null
    }

    /**
     * 配置済みのグラフファイル (.graph) と対応するインデックス (.graph.idx) のペアのうち、
     * ファイル名昇順で最初に見つかった有効ペアを返す。
     */
    fun findFirstAvailableGraph(dataDir: File): Pair<File, File>? {
        val files = dataDir.listFiles() ?: return null
        val graphFiles = files.filter { it.isFile && it.name.endsWith(".graph", ignoreCase = true) }
            .sortedBy { it.name }
        for (graphFile in graphFiles) {
            val resolved = resolveGraphFiles(dataDir, graphFile.name)
            if (resolved != null) return resolved
        }
        return null
    }
}

/** 検索DBの選択結果。 */
sealed interface SearchDbSelection {
    /** 対応DBあり。fileName は CycleMap データ dir 直下の実ファイル名。 */
    data class Available(
        val prefectureId: String,
        val prefectureName: String,
        val fileName: String,
    ) : SearchDbSelection

    /** 対応DBなし (未対応県・県判定不能)。他県DBの使い回しは禁止のため呼び出し側は失敗させる。 */
    data class Unavailable(val prefectureName: String?) : SearchDbSelection
}

/**
 * 現在地→県→検索DB (search.db系) の選択 (純粋関数・JVMテスト可能)。
 * 県判定は既存 [findPrefectureName]、県定義は既存 [PrefectureData.ALL] を再利用する。
 */
object SearchDbSelector {
    /**
     * 県別検索DBの名前 (県ID → ファイル名)。47 県すべてを `<Romaji>.search.db` で引く。
     * yamaguchi は既配備の search.db (山口県内容) を先頭候補にする (`resolveDbFile` が Yamaguchi.search.db も探す)。
     * ファイルの実在は呼び出し側が `resolveDbFile` で確かめる。
     */
    internal val SUPPORTED_FILES: Map<String, String> = PrefectureData.ALL.associate { pref ->
        pref.id to if (pref.id == "yamaguchi") "search.db" else "${pref.id.replaceFirstChar { it.uppercase() }}.search.db"
    }

    fun select(latitude: Double, longitude: Double): SearchDbSelection {
        val name = findPrefectureName(latitude, longitude)
        val pref = PrefectureData.ALL.firstOrNull { it.name == name }
        val file = pref?.let { SUPPORTED_FILES[it.id] }
        return if (pref != null && file != null) {
            SearchDbSelection.Available(pref.id, pref.name, file)
        } else {
            SearchDbSelection.Unavailable(name)
        }
    }

    /**
     * 実在する検索DBファイルを解決する。
     * パイプライン生成物 (Yamaguchi.search.db) と既存配備 (search.db) の
     * プレフィックス・大文字小文字の違いをフォールバック解決する (BUG-65)。
     */
    fun resolveDbFile(dataDir: File, fileName: String): File? {
        val primary = File(dataDir, fileName)
        if (primary.isFile) return primary

        val candidates = when (fileName) {
            "search.db" -> listOf("Yamaguchi.search.db", "yamaguchi.search.db")
            "Yamaguchi.search.db", "yamaguchi.search.db" -> listOf("search.db")
            else -> {
                val cap = fileName.replaceFirstChar { it.uppercase() }
                if (cap == fileName) listOf(fileName.replaceFirstChar { it.lowercase() }) else listOf(cap)
            }
        }
        for (cand in candidates) {
            val f = File(dataDir, cand)
            if (f.isFile) return f
        }
        return null
    }

    /**
     * 配置済みの検索DB (.search.db または search.db) のうち、
     * ファイル名昇順で最初に見つかった実在ファイルを返す。
     */
    fun findFirstAvailableDb(dataDir: File): File? {
        val files = dataDir.listFiles() ?: return null
        val dbFiles = files.filter { file ->
            file.isFile && (file.name.endsWith(".search.db", ignoreCase = true) || file.name.equals("search.db", ignoreCase = true))
        }.sortedBy { it.name }
        for (dbFile in dbFiles) {
            val resolved = resolveDbFile(dataDir, dbFile.name)
            if (resolved != null) return resolved
        }
        return null
    }
}

/** PMTiles 用県ベクタータイルの選択結果 (P3-0) */
sealed interface TileSourceSelection {
    data class Available(
        val prefectureId: String,
        val prefectureName: String,
        val fileName: String
    ) : TileSourceSelection

    data class Unavailable(val prefectureName: String?) : TileSourceSelection
}

/**
 * 現在地→県→ベクタータイル (PMTiles) の選択。
 * 県判定は既存 findPrefectureName を再利用。
 */
object TileSourceSelector {
    /** 県ID → `<id>.pmtiles` (47 県)。旧名 Hiroshima_osm.pmtiles などは resolveTileFile の互換候補で解決する。 */
    internal val SUPPORTED_PMTILES: Map<String, String> = PrefectureData.ALL.associate { it.id to "${it.id}.pmtiles" }

    fun select(latitude: Double, longitude: Double): TileSourceSelection {
        val name = findPrefectureName(latitude, longitude)
        val pref = PrefectureData.ALL.firstOrNull { it.name == name }
        val file = pref?.let { SUPPORTED_PMTILES[it.id] }
        return if (pref != null && file != null) {
            TileSourceSelection.Available(pref.id, pref.name, file)
        } else {
            TileSourceSelection.Unavailable(name)
        }
    }

    fun resolveTileFile(dataDir: File, fileName: String): File? {
        val primary = File(dataDir, fileName)
        if (primary.isFile && primary.length() > 0) return primary

        val tilesSub = File(dataDir, "tiles/$fileName")
        if (tilesSub.isFile && tilesSub.length() > 0) return tilesSub

        val baseName = fileName.removeSuffix(".pmtiles").removeSuffix("_osm")
        val candidateNames = listOf(
            fileName,
            fileName.lowercase(),
            fileName.replaceFirstChar { it.uppercase() },
            "$baseName.pmtiles",
            "${baseName.lowercase()}.pmtiles",
            "${baseName.replaceFirstChar { it.uppercase() }}.pmtiles",
            "${baseName}_osm.pmtiles",
            "${baseName.lowercase()}_osm.pmtiles",
            "${baseName.replaceFirstChar { it.uppercase() }}_osm.pmtiles",
        ).distinct()

        for (name in candidateNames) {
            val f1 = File(dataDir, name)
            if (f1.isFile && f1.length() > 0) return f1
            val f2 = File(dataDir, "tiles/$name")
            if (f2.isFile && f2.length() > 0) return f2
        }
        return null
    }

    /**
     * 配置済みの PMTiles のうち、ファイル名昇順で最初に見つかった実在ファイルを返す。
     */
    fun findFirstAvailableTile(dataDir: File): File? {
        val rootFiles = dataDir.listFiles() ?: emptyArray()
        val tilesSubDir = File(dataDir, "tiles")
        val subFiles = if (tilesSubDir.isDirectory) tilesSubDir.listFiles() ?: emptyArray() else emptyArray()
        val allFiles = (rootFiles.toList() + subFiles.toList()).filter { file ->
            file.isFile && file.name.endsWith(".pmtiles", ignoreCase = true) && file.length() > 0
        }.sortedBy { it.name }
        return allFiles.firstOrNull()
    }

    fun resolveTileForLocation(dataDir: File, latitude: Double, longitude: Double): File? {
        val selection = select(latitude, longitude)
        if (selection !is TileSourceSelection.Available) return null
        return resolveTileFile(dataDir, selection.fileName)
    }
}
