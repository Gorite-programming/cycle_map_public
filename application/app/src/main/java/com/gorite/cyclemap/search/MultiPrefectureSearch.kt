package com.gorite.cyclemap.search

import android.os.CancellationSignal
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import com.gorite.cyclemap.data.Prefecture
import com.gorite.cyclemap.data.PrefectureData
import com.gorite.cyclemap.data.SearchDbSelector
import com.gorite.cyclemap.navigation.PREFECTURE_ADJACENCY
import java.io.File

internal data class SearchDatabase(val file: File, val prefectureName: String?)

internal fun resolveSearchDatabaseForPrefecture(dataDir: File, prefecture: Prefecture): SearchDatabase? {
    val name = SearchDbSelector.SUPPORTED_FILES[prefecture.id] ?: return null
    return SearchDbSelector.resolveDbFile(dataDir, name)
        ?.takeIf { it.isFile }
        ?.let { file -> SearchDatabase(file, prefecture.name) }
}

internal fun availableSearchDatabases(dataDir: File, primary: File): List<SearchDatabase> {
    val pref = PrefectureData.ALL.firstOrNull {
        primary.name.equals("${it.id}.search.db", ignoreCase = true) ||
            (it.id == "yamaguchi" && primary.name == "search.db")
    }
    val names = pref?.let { PREFECTURE_ADJACENCY[it.name].orEmpty() + it.name }.orEmpty()
    return (listOf(SearchDatabase(primary, pref?.name)) + PrefectureData.ALL.filter { it.name in names }.mapNotNull {
        resolveSearchDatabaseForPrefecture(dataDir, it)
    }).filter { it.file.isFile }.distinctBy { it.file.absolutePath }
}

internal fun mergeSearchResults(groups: List<List<SearchResult>>, query: String, lat: Double?, lon: Double?, limit: Int = 50): List<SearchResult> {
    val ranked = rankSearchResults(groups.flatten(), query, lat, lon)
    val ordered = if (query.isBlank()) ranked.sortedBy { it.distanceMeters ?: Double.MAX_VALUE } else ranked
    return clusterBusStops(ordered).let { rank ->
        // Clustering groups bus stops separately, so restore the global ordering afterwards.
        if (query.isBlank()) rank.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
        else rankSearchResults(rank, query, lat, lon)
    }.take(limit)
}

internal suspend fun searchAcrossPrefectures(
    databases: List<SearchDatabase>, query: String, category: SearchCategory,
    userLat: Double?, userLon: Double?, categoryPrefixesOverride: List<String>? = null,
    restrictCategoryToOrigin: Boolean = true,
): List<SearchResult> {
    val signal = CancellationSignal()
    return debouncedSearch(signal::cancel) {
        searchCancellation.set(signal)
        try {
            val groups = databases.map { source ->
                signal.throwIfCanceled()
                try {
                    searchPlaces(source.file, query, category, userLat, userLon,
                        categoryPrefixesOverride = categoryPrefixesOverride,
                        restrictCategoryToOrigin = restrictCategoryToOrigin).map { it.copy(prefectureName = source.prefectureName) }
                } catch (e: android.os.OperationCanceledException) {
                    throw e
                } catch (e: android.database.sqlite.SQLiteException) {
                    android.util.Log.w("CycleMap", "Search failed: ${source.file.name}", e)
                    emptyList()
                }
            }
            signal.throwIfCanceled()
            mergeSearchResults(groups, query, userLat, userLon)
        } finally {
            searchCancellation.remove()
        }
    }
}

internal val searchCancellation = ThreadLocal<CancellationSignal>()

internal fun SQLiteDatabase.searchQuery(sql: String, args: Array<String>?): Cursor {
    val signal = searchCancellation.get()
    signal?.throwIfCanceled()
    return rawQuery(sql, args, signal)
}
