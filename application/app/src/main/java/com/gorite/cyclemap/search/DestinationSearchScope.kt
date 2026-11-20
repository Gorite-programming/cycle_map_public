package com.gorite.cyclemap.search

import java.io.File
import com.gorite.cyclemap.data.PrefectureData

internal enum class DestinationSearchScope(val label: String) {
    NEARBY("現在地周辺"), MAP("表示中の地図"), INSTALLED("導入済みの全県"),
}

internal fun destinationSearchOrigin(
    scope: DestinationSearchScope,
    currentLocation: Pair<Double, Double>?,
    mapCenter: Pair<Double, Double>?,
): Pair<Double, Double>? = if (scope == DestinationSearchScope.MAP) mapCenter else currentLocation

/** Selection stays pure; file discovery and prefecture lookup happen at the caller. */
internal fun destinationSearchDatabases(
    scope: DestinationSearchScope,
    nearby: List<SearchDatabase>,
    installed: List<SearchDatabase>,
): List<SearchDatabase> = (if (scope == DestinationSearchScope.INSTALLED) installed else nearby)
    .distinctBy { it.file.absolutePath }

internal fun installedSearchDatabases(dataDir: File): List<SearchDatabase> =
    dataDir.listFiles().orEmpty().filter {
        it.isFile && (it.name.endsWith(".search.db", true) || it.name.equals("search.db", true))
    }.sortedBy { it.name }.map { file ->
        val prefecture = PrefectureData.ALL.firstOrNull {
            file.name.equals("${it.id}.search.db", true) ||
                (it.id == "yamaguchi" && file.name.equals("search.db", true))
        }
        SearchDatabase(file, prefecture?.name)
    }.distinctBy { it.prefectureName ?: it.file.absolutePath }

/** Include scope even when origin and database paths happen to be identical. */
internal data class ScopedDestinationSearchRequest<T>(val request: T, val scope: DestinationSearchScope)
