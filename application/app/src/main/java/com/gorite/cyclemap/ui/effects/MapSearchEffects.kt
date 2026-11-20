package com.gorite.cyclemap.ui.effects

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.gorite.cyclemap.R
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.utils.cycleMapDataDir
import com.gorite.cyclemap.data.SearchDbSelection
import com.gorite.cyclemap.data.SearchDbSelector
import com.gorite.cyclemap.search.activeRemainingRoutePoints
import com.gorite.cyclemap.search.findMapViewModelOrNull
import com.gorite.cyclemap.search.mergeQuickSpotResults
import com.gorite.cyclemap.search.searchNearbyPlaces
import com.gorite.cyclemap.search.searchNearbyQuickSpots
import com.gorite.cyclemap.search.routeSearchCenters
import com.gorite.cyclemap.search.selectRouteQuickSpotDatabases
import com.gorite.cyclemap.ui.cycling.NearbySpot
import com.gorite.cyclemap.ui.cycling.QuickSpotType
import com.gorite.cyclemap.ui.cycling.SpotQuickCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
internal fun MapSearchEffects(
    spotCategory: SpotQuickCategory?,
    showSpotPanel: Boolean,
    currentLocation: Location?,
    mapView: MapView?,
    vectorMap: MapLibreMap? = null,
    context: Context,
    onSpotLoadingChange: (Boolean) -> Unit,
    onSpotMessageChange: (String?) -> Unit,
    onSpotResultsChange: (List<NearbySpot>) -> Unit,
    activeQuickSpotType: QuickSpotType?,
    showQuickSpotSheet: Boolean,
    onQuickSpotLoadingChange: (Boolean) -> Unit,
    onQuickSpotMessageChange: (String?) -> Unit,
    onQuickSpotResultsChange: (List<NearbySpot>) -> Unit,
    selectedPoiSpot: NearbySpot?,
    isNavigationActive: Boolean? = null,
    navigationRoute: List<RoutePoint>? = null,
    navigationProgress: RouteProgress? = null,
) {
    val mapViewModel = remember(context) { findMapViewModelOrNull(context) }
    val effectiveNavigating = isNavigationActive ?: (mapViewModel?.navigation?.isActive?.value == true)
    val effectiveRoute = navigationRoute ?: mapViewModel?.route?.points?.value.orEmpty()
    val effectiveProgress = navigationProgress ?: mapViewModel?.navigation?.progress?.value
    // スポット検索 (カテゴリ選択→周辺のオフラインDB検索)
    LaunchedEffect(spotCategory, showSpotPanel) {
        val cat = spotCategory
        if (!showSpotPanel || cat == null) return@LaunchedEffect
        onSpotLoadingChange(true)
        onSpotMessageChange(null)
        val center = currentLocation?.let { it.latitude to it.longitude }
            ?: (vectorMap?.cameraPosition?.target)?.let { it.latitude to it.longitude }
            ?: (mapView?.mapCenter as? GeoPoint)?.let { it.latitude to it.longitude }
        if (center == null) {
            onSpotLoadingChange(false)
            onSpotMessageChange("現在地が取得できません")
            return@LaunchedEffect
        }
        val prefixes = cat.poi.prefixes
        val dbSelection = SearchDbSelector.select(center.first, center.second)
        val dataDir = cycleMapDataDir(context)
        val dbFile = when (dbSelection) {
            is SearchDbSelection.Available -> SearchDbSelector.resolveDbFile(dataDir, dbSelection.fileName)
            is SearchDbSelection.Unavailable -> null
        }
        if (dbSelection is SearchDbSelection.Available) {
            Log.i(
                "CycleMapGeo",
                "POI_DB panel prefecture=${dbSelection.prefectureName} file=${dbSelection.fileName} exists=${dbFile?.isFile}",
            )
        }
        var readFailed = false
        val found = try {
            if (dbFile != null && dbFile.isFile) {
                withContext(Dispatchers.IO) {
                    searchNearbyPlaces(dbFile, center.first, center.second, 3000.0, limit = 30, categoryPrefixes = prefixes)
                }
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CycleMap", "Spot search DB read failed", e)
            readFailed = true
            emptyList()
        }
        onSpotResultsChange(found)
        onSpotLoadingChange(false)
        onSpotMessageChange(
            if (readFailed) {
                "検索DBを読めません"
            } else if (dbFile == null || !dbFile.isFile) {
                "検索DBが見つかりません"
            } else if (found.isEmpty()) {
                "周辺に${cat.label}が見つかりませんでした"
            } else {
                null
            },
        )
    }

    // クイックスポット検索 (QuickSpotType選択→周辺のオフラインDB検索)
    LaunchedEffect(activeQuickSpotType, showQuickSpotSheet) {
        val type = activeQuickSpotType
        if (!showQuickSpotSheet || type == null) return@LaunchedEffect
        onQuickSpotLoadingChange(true)
        onQuickSpotResultsChange(emptyList())
        onQuickSpotMessageChange(null)
        val center = currentLocation?.let { it.latitude to it.longitude }
            ?: (vectorMap?.cameraPosition?.target)?.let { it.latitude to it.longitude }
            ?: (mapView?.mapCenter as? GeoPoint)?.let { it.latitude to it.longitude }
        if (center == null) {
            onQuickSpotLoadingChange(false)
            onQuickSpotMessageChange("現在地が取得できません")
            return@LaunchedEffect
        }
        val dataDir = cycleMapDataDir(context)
        if (!effectiveNavigating) {
            val dbSelection = SearchDbSelector.select(center.first, center.second)
            val dbFile = when (dbSelection) {
                is SearchDbSelection.Available -> SearchDbSelector.resolveDbFile(dataDir, dbSelection.fileName)
                is SearchDbSelection.Unavailable -> null
            }
            if (dbSelection is SearchDbSelection.Available) {
                Log.i(
                    "CycleMapGeo",
                    "QUICK_SPOT_DB prefecture=${dbSelection.prefectureName} file=${dbSelection.fileName} type=${type.name}",
                )
            }
            var readFailed = false
            val found = try {
                if (dbFile != null && dbFile.isFile) {
                    withContext(Dispatchers.IO) {
                        searchNearbyQuickSpots(
                            dbFile = dbFile,
                            centerLat = center.first,
                            centerLon = center.second,
                            radiusMeters = 8000.0,
                            categoryKeys = type.categoryKeys,
                            nameFilter = type.nameFilter,
                            limit = 30,
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CycleMap", "Quick spot search DB read failed", e)
                readFailed = true
                emptyList()
            }
            onQuickSpotResultsChange(found)
            onQuickSpotLoadingChange(false)
            onQuickSpotMessageChange(
                if (readFailed) {
                    "検索DBを読めません"
                } else if (dbFile == null || !dbFile.isFile) {
                    "検索DBが見つかりません"
                } else if (found.isEmpty()) {
                    "周辺に${type.label}が見つかりませんでした"
                } else {
                    null
                },
            )
            return@LaunchedEffect
        }

        val remainingRoute = activeRemainingRoutePoints(
            isNavigating = true,
            routePoints = effectiveRoute,
            progress = effectiveProgress,
        )
        var noDbAvailable = false
        var readFailed = false
        val found = try {
            withContext(Dispatchers.IO) {
                val targetDbs = selectRouteQuickSpotDatabases(
                    dataDir = dataDir,
                    centerLat = center.first,
                    centerLon = center.second,
                    remainingRoute = remainingRoute,
                )
                if (targetDbs.isEmpty()) {
                    noDbAvailable = true
                    return@withContext emptyList()
                }
                Log.i(
                    "CycleMapGeo",
                    "QUICK_SPOT_DB nav prefectures=${targetDbs.joinToString(",") { it.prefectureName ?: it.file.name }} " +
                        "files=${targetDbs.joinToString(",") { it.file.name }} type=${type.name}",
                )
                var failedCount = 0
                // 現在地だけでなく残りの経路に沿った先の地点でも探し、県境の先の候補も拾う
                val centers = routeSearchCenters(remainingRoute).map { it.latitude to it.longitude }
                    .ifEmpty { listOf(center) }
                    .let { if (it.first() != center) listOf(center) + it else it }
                val groups = targetDbs.flatMap { db ->
                    centers.map { c ->
                        try {
                            searchNearbyQuickSpots(
                                dbFile = db.file,
                                centerLat = c.first,
                                centerLon = c.second,
                                radiusMeters = 8000.0,
                                categoryKeys = type.categoryKeys,
                                nameFilter = type.nameFilter,
                                limit = 30,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("CycleMap", "Quick spot search DB read failed: ${db.file.name}", e)
                            failedCount++
                            emptyList()
                        }
                    }
                }
                // 各検索の距離は、その検索の中心からのもの。表示・重複判定の基準は現在地にそろえる
                val groupsFromHere = groups.map { group ->
                    group.map { it.copy(distanceM = com.gorite.cyclemap.utils.haversineMeters(center.first, center.second, it.latitude, it.longitude)) }
                }
                if (failedCount == targetDbs.size * centers.size) {
                    readFailed = true
                    emptyList()
                } else {
                    mergeQuickSpotResults(
                        spotGroups = groupsFromHere,
                        remainingRoute = remainingRoute,
                        limit = 30,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CycleMap", "Quick spot route search failed", e)
            readFailed = true
            emptyList()
        }
        onQuickSpotResultsChange(found)
        onQuickSpotLoadingChange(false)
        onQuickSpotMessageChange(
            if (readFailed) {
                "検索DBを読めません"
            } else if (noDbAvailable) {
                "検索DBが見つかりません"
            } else if (found.isEmpty()) {
                "周辺に${type.label}が見つかりませんでした"
            } else {
                null
            },
        )
    }

    // クイックスポットやPOI選択時のハイライトピン描画 (osmdroid ラスタレイヤ用)
    LaunchedEffect(selectedPoiSpot, mapView) {
        val view = mapView ?: return@LaunchedEffect
        view.overlays.removeAll { it is Marker && it.title == "SELECTED_POI_HIGHLIGHT" }
        val spot = selectedPoiSpot
        if (spot != null) {
            val cat = com.gorite.cyclemap.ui.cycling.PoiCategory.forCategory(spot.category)
            val marker = Marker(view).apply {
                title = "SELECTED_POI_HIGHLIGHT"
                position = GeoPoint(spot.latitude, spot.longitude)
                icon = if (cat != null) {
                    com.gorite.cyclemap.utils.googlePoiMarker(context, cat, "", showLabel = false).drawable
                } else {
                    android.graphics.drawable.BitmapDrawable(
                        context.resources,
                        com.gorite.cyclemap.ui.mapview.createGoogleSolidPinBitmap(context),
                    )
                }
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                infoWindow = null
            }
            view.overlays.add(marker)
        }
        view.invalidate()
    }
}
