package com.gorite.cyclemap.search

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.gorite.cyclemap.contract.NearbySpot
import com.gorite.cyclemap.data.PrefectureData
import com.gorite.cyclemap.data.SearchDbSelection
import com.gorite.cyclemap.data.SearchDbSelector
import com.gorite.cyclemap.data.findPrefectureName
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.ui.cycling.sortSpotsByRouteProximity
import com.gorite.cyclemap.ui.map.MapViewModel
import com.gorite.cyclemap.utils.haversineMeters
import java.io.File

/**
 * 案内経路と現在の進捗から、残りの経路だけの座標列を返す。
 * ナビ中でない場合、進捗が null・非有限・0 以下、または経路点が 2 点未満の場合は全経路をそのまま返す。
 */
internal fun activeRemainingRoutePoints(
    isNavigating: Boolean,
    routePoints: List<RoutePoint>,
    progress: RouteProgress?,
): List<RoutePoint> =
    if (!isNavigating) routePoints.toList() else remainingRoutePoints(routePoints, progress)

/**
 * 案内経路と現在の進捗 ([RouteProgress]) から、残りの経路だけの座標列を返す。
 * 進捗が無い場合は全経路をそのまま返す。
 */
internal fun remainingRoutePoints(
    routePoints: List<RoutePoint>,
    progress: RouteProgress?,
): List<RoutePoint> = remainingRoutePoints(
    routePoints = routePoints,
    traveledMeters = progress?.distanceFromStartMeters,
)

/**
 * 始点からの走行済み距離 ([traveledMeters]) 以降に残っている経路の座標列を返す。
 * 現在位置が線分の途中にある場合はその補間点を新しい始点とし、以降の全頂点を含める。
 */
internal fun remainingRoutePoints(
    routePoints: List<RoutePoint>,
    traveledMeters: Double?,
): List<RoutePoint> {
    if (routePoints.size < 2 || traveledMeters == null || !traveledMeters.isFinite() || traveledMeters <= 0.0) {
        return routePoints.toList()
    }
    var cumulative = 0.0
    for (index in 0 until routePoints.lastIndex) {
        val start = routePoints[index]
        val end = routePoints[index + 1]
        val segMeters = haversineMeters(start.latitude, start.longitude, end.latitude, end.longitude)
        val nextCumulative = cumulative + segMeters
        if (traveledMeters < nextCumulative) {
            if (segMeters <= 0.0) {
                return routePoints.subList(index, routePoints.size).toList()
            }
            val fraction = ((traveledMeters - cumulative) / segMeters).coerceIn(0.0, 1.0)
            return when {
                fraction < 1e-6 -> routePoints.subList(index, routePoints.size).toList()
                fraction > 1.0 - 1e-6 -> routePoints.subList(index + 1, routePoints.size).toList()
                else -> {
                    val splitPoint = RoutePoint(
                        latitude = start.latitude + (end.latitude - start.latitude) * fraction,
                        longitude = start.longitude + (end.longitude - start.longitude) * fraction,
                    )
                    buildList(routePoints.size - index) {
                        add(splitPoint)
                        addAll(routePoints.subList(index + 1, routePoints.size))
                    }
                }
            }
        }
        cumulative = nextCumulative
    }
    return listOf(routePoints.last())
}

/**
 * 経路の座標列が通過する都道府県名を、通過順に重複なく列挙する。
 * 密な経路での過剰なポリゴン判定を避けるため一定距離ごとにサンプリングしつつ、始点・終点は必ず判定する。
 */
internal fun prefecturesAlongRoute(
    routePoints: List<RoutePoint>,
    prefectureLookup: (Double, Double) -> String? = ::findPrefectureName,
    sampleIntervalMeters: Double = 300.0,
): List<String> {
    if (routePoints.isEmpty()) return emptyList()
    val result = LinkedHashSet<String>()
    var lastChecked: RoutePoint? = null
    for (index in routePoints.indices) {
        val point = routePoints[index]
        if (!point.latitude.isFinite() || !point.longitude.isFinite()) continue
        val isEndpoint = index == 0 || index == routePoints.lastIndex
        val shouldCheck = isEndpoint ||
            routePoints.size <= 100 ||
            lastChecked == null ||
            haversineMeters(lastChecked.latitude, lastChecked.longitude, point.latitude, point.longitude) >= sampleIntervalMeters
        if (shouldCheck) {
            prefectureLookup(point.latitude, point.longitude)?.let { result.add(it) }
            lastChecked = point
        }
    }
    return result.toList()
}

/**
 * 残りの経路に沿って、先の候補を探す中心点を返す。先頭は残りの経路の始点 (現在地付近)、以降は
 * [stepMeters] ごとに [maxAheadMeters] まで。県境の先など、現在地から [step] を超えて離れた候補も拾える。
 */
internal fun routeSearchCenters(
    remainingRoute: List<RoutePoint>,
    stepMeters: Double = 12_000.0,
    maxAheadMeters: Double = 48_000.0,
): List<RoutePoint> {
    if (remainingRoute.isEmpty()) return emptyList()
    val centers = arrayListOf(remainingRoute.first())
    var cumulative = 0.0
    var nextAt = stepMeters
    for (i in 0 until remainingRoute.lastIndex) {
        val a = remainingRoute[i]
        val b = remainingRoute[i + 1]
        val seg = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        while (seg > 0.0 && nextAt <= cumulative + seg && nextAt <= maxAheadMeters) {
            val f = (nextAt - cumulative) / seg
            centers += RoutePoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
            nextAt += stepMeters
        }
        cumulative += seg
        if (cumulative > maxAheadMeters) break
    }
    return centers
}

/**
 * 現在地の県 + 残りの経路が通る県に対応する、端末に実在する検索 DB 一覧を返す。
 * 既存の [availableSearchDatabases]・[SearchDbSelector]・[PrefectureData] を再利用する。
 */
internal fun selectRouteQuickSpotDatabases(
    dataDir: File,
    centerLat: Double,
    centerLon: Double,
    remainingRoute: List<RoutePoint>,
    prefectureLookup: (Double, Double) -> String? = ::findPrefectureName,
): List<SearchDatabase> {
    val currentPref = if (centerLat.isFinite() && centerLon.isFinite()) {
        prefectureLookup(centerLat, centerLon)
    } else {
        null
    }
    val targetPrefectures = (listOfNotNull(currentPref) + prefecturesAlongRoute(remainingRoute, prefectureLookup)).distinct()
    if (targetPrefectures.isEmpty()) return emptyList()

    val primaryFile = currentPref?.let { prefName ->
        PrefectureData.ALL.firstOrNull { it.name == prefName }
            ?.let { SearchDbSelector.SUPPORTED_FILES[it.id] }
            ?.let { fileName -> SearchDbSelector.resolveDbFile(dataDir, fileName) }
    } ?: (SearchDbSelector.select(centerLat, centerLon) as? SearchDbSelection.Available)
        ?.let { SearchDbSelector.resolveDbFile(dataDir, it.fileName) }

    val targetSet = targetPrefectures.toSet()
    val fromAvailable = primaryFile
        ?.takeIf { it.isFile }
        ?.let { availableSearchDatabases(dataDir, it).filter { db -> db.prefectureName in targetSet } }
        .orEmpty()
        .associateBy { it.prefectureName }

    val prefByName = PrefectureData.ALL.associateBy { it.name }
    return targetPrefectures.mapNotNull { prefName ->
        fromAvailable[prefName]
            ?: prefByName[prefName]?.let { resolveSearchDatabaseForPrefecture(dataDir, it) }
    }.distinctBy { it.file.absolutePath }
}

/**
 * 同名かつ近接 ([radiusMeters] 以内) な候補を重複排除する。
 * 住所情報を持つ候補や先に出現した候補を優先して残す。
 */
internal fun dedupeNearbySpots(
    spots: List<NearbySpot>,
    radiusMeters: Double = 30.0,
): List<NearbySpot> {
    val kept = ArrayList<NearbySpot>(spots.size)
    val keptKeys = ArrayList<String>(spots.size)
    for (spot in spots) {
        val key = SearchNormalizer.normalize(spot.name).replace(" ", "")
        val dupIndex = kept.indices.firstOrNull { i ->
            keptKeys[i] == key &&
                haversineMeters(kept[i].latitude, kept[i].longitude, spot.latitude, spot.longitude) <= radiusMeters
        }
        if (dupIndex == null) {
            kept += spot
            keptKeys += key
        } else if (kept[dupIndex].address.isNullOrBlank() && !spot.address.isNullOrBlank()) {
            kept[dupIndex] = spot
        }
    }
    return kept
}

/**
 * 複数 DB から得たクイックスポット候補を統合し、重複を除いて上位 [limit] 件を返す。
 * 有効な残り経路がある場合は残り経路への距離順 (同距離なら現在地からの距離順) で優先する。
 */
internal fun mergeQuickSpotResults(
    spotGroups: List<List<NearbySpot>>,
    remainingRoute: List<RoutePoint> = emptyList(),
    limit: Int = 30,
): List<NearbySpot> {
    if (limit <= 0) return emptyList()
    val deduped = dedupeNearbySpots(spotGroups.flatten().sortedBy { it.distanceM })
    if (remainingRoute.size >= 2) {
        return sortSpotsByRouteProximity(deduped, remainingRoute)
            .take(limit)
            .map { it.spot }
    }
    return deduped.sortedBy { it.distanceM }.take(limit)
}

internal fun findMapViewModelOrNull(context: Context): MapViewModel? {
    val owner = context.findViewModelStoreOwner() ?: return null
    return runCatching { ViewModelProvider(owner)[MapViewModel::class.java] }.getOrNull()
}

private tailrec fun Context.findViewModelStoreOwner(): ViewModelStoreOwner? = when (this) {
    is ViewModelStoreOwner -> this
    is ContextWrapper -> baseContext.findViewModelStoreOwner()
    else -> null
}
