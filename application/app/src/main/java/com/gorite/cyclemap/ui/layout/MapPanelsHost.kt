package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import com.gorite.cyclemap.data.FavoriteSpot
import com.gorite.cyclemap.data.FavoritesManager
import com.gorite.cyclemap.ui.mapview.resolveSearchDbFile
import com.gorite.cyclemap.routing.NavigationStats
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.ui.cycling.CyclingTab
import com.gorite.cyclemap.ui.cycling.ElevationSample
import com.gorite.cyclemap.ui.cycling.NearbySpot
import com.gorite.cyclemap.ui.cycling.QuickSpotBottomSheet
import com.gorite.cyclemap.ui.cycling.QuickSpotType
import com.gorite.cyclemap.ui.cycling.SpotPanelSheet
import com.gorite.cyclemap.ui.cycling.SpotQuickCategory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

@Composable
internal fun MapPanelsHost(
    isNavigationActive: Boolean = false,
    navigationRoute: List<com.gorite.cyclemap.routing.RoutePoint> = emptyList(),
    showSpotPanel: Boolean,
    onCloseSpotPanel: () -> Unit,
    spotCategory: SpotQuickCategory?,
    onSpotCategoryChange: (SpotQuickCategory) -> Unit,
    spotLoading: Boolean,
    spotMessage: String?,
    spotResults: List<NearbySpot>,
    onQuickSpotTypeSelectFromSpotPanel: (QuickSpotType) -> Unit,
    onRouteToSpot: (GeoPoint) -> Unit,
    showFavoritesOnlyInSheet: Boolean,
    showQuickSpotSheet: Boolean,
    onCloseQuickSpotSheet: () -> Unit,
    activeQuickSpotType: QuickSpotType?,
    onActiveQuickSpotTypeChange: (QuickSpotType) -> Unit,
    quickSpotLoading: Boolean,
    quickSpotMessage: String?,
    quickSpotResults: List<NearbySpot>,
    onSelectPoiSpot: (NearbySpot?) -> Unit,
    mapView: MapView?,
    vectorMap: org.maplibre.android.maps.MapLibreMap? = null,
    selectedPoiSpot: NearbySpot? = null,
    /** 現在地 (緯度, 経度)。一覧と詳細で距離の横に方角を出すのに使う。 */
    origin: Pair<Double, Double>? = null,
) {
    // 契約 PlaceDetail 3段階アンカーボトムシート (Peek120 / Half320 / Full560)
    val placeDetailHeightPx = LocalPlaceDetailHeightPx.current
    var sheetAnchor by androidx.compose.runtime.remember(selectedPoiSpot) {
        androidx.compose.runtime.mutableStateOf(com.gorite.cyclemap.ui.map.BottomSheetAnchor.Half)
    }

    var placeDetail by androidx.compose.runtime.remember(selectedPoiSpot) {
        val initial = selectedPoiSpot?.let { spot ->
            val catLabel = com.gorite.cyclemap.ui.cycling.PoiCategory.forCategory(spot.category)?.label ?: com.gorite.cyclemap.search.formatCategoryLabel(spot.category)
            com.gorite.cyclemap.contract.PlaceDetail(
                id = "${spot.name}_${spot.latitude}_${spot.longitude}",
                name = spot.name,
                category = catLabel,
                address = "",
                distanceMeters = spot.distanceM,
                lat = spot.latitude,
                lon = spot.longitude,
                businessStatus = com.gorite.cyclemap.contract.BusinessStatus.Unknown
            )
        }
        androidx.compose.runtime.mutableStateOf(initial)
    }

    val localContext = androidx.compose.ui.platform.LocalContext.current
    val favoritesManager = remember {
        FavoritesManager.getInstance(localContext)
    }
    val favoritesList by favoritesManager.favorites.collectAsState()

    androidx.compose.runtime.LaunchedEffect(selectedPoiSpot) {
        val spot = selectedPoiSpot ?: return@LaunchedEffect
        val provider = com.gorite.cyclemap.real.RealPlaceDetailProvider(
            searchDbFileProvider = {
                resolveSearchDbFile(localContext, spot.latitude, spot.longitude)
            }
        )
        val loaded = provider.getPlaceDetailNear(spot.name, spot.latitude, spot.longitude)
        val catLabel = com.gorite.cyclemap.ui.cycling.PoiCategory.forCategory(spot.category)?.label ?: com.gorite.cyclemap.search.formatCategoryLabel(spot.category)
        val loadedDist = loaded?.distanceMeters
        if (loaded != null) {
            placeDetail = loaded.copy(
                category = com.gorite.cyclemap.ui.cycling.PoiCategory.forCategory(loaded.category)?.label ?: catLabel,
                distanceMeters = if (loadedDist != null && loadedDist > 0.0) loadedDist else spot.distanceM,
                lat = spot.latitude,
                lon = spot.longitude,
            )
        }
    }

    if (showSpotPanel && selectedPoiSpot == null && placeDetail == null) {
        androidx.activity.compose.BackHandler(onBack = onCloseSpotPanel)
        SpotPanelSheet(
            selectedCategory = spotCategory,
            onCategorySelect = onSpotCategoryChange,
            isLoading = spotLoading,
            spots = spotResults,
            message = spotMessage,
            onSpotClick = { spot ->
                onSelectPoiSpot(spot)
            },
            onQuickSpotTypeSelect = onQuickSpotTypeSelectFromSpotPanel,
            onRouteToSpot = { spot ->
                onCloseSpotPanel()
                onRouteToSpot(GeoPoint(spot.latitude, spot.longitude))
            },
            onDismiss = onCloseSpotPanel,
            initialShowFavoritesOnly = showFavoritesOnlyInSheet,
            origin = origin,
        )
    }

    if (showQuickSpotSheet && activeQuickSpotType != null) {
        QuickSpotBottomSheet(
            isNavigating = isNavigationActive,
            routePoints = navigationRoute,
            selectedType = activeQuickSpotType,
            onSelectType = onActiveQuickSpotTypeChange,
            spots = quickSpotResults,
            isLoading = quickSpotLoading,
            errorMessage = quickSpotMessage,
            onSpotClick = { spot ->
                onCloseQuickSpotSheet()
                // カメラは施設選択の共通の処理 (余白を反映してから 1 回だけ寄せる) に任せる。ここで直接動かすと競合してピンが隠れる
                onSelectPoiSpot(spot)
            },
            onRouteClick = { spot ->
                onCloseQuickSpotSheet()
                onRouteToSpot(GeoPoint(spot.latitude, spot.longitude))
            },
            onDismiss = onCloseQuickSpotSheet,
            origin = origin,
        )
    }

    if (showSpotPanel && placeDetail != null) {
        val currentDetail = placeDetail!!
        // 戻るボタンはアプリを閉じずに、まず場所のシートを閉じる
        androidx.activity.compose.BackHandler {
            placeDetail = null
            onSelectPoiSpot(null)
            onCloseSpotPanel()
        }
        val isFav = favoritesList.any {
            kotlin.math.abs(it.latitude - currentDetail.lat) < 0.0001 &&
                kotlin.math.abs(it.longitude - currentDetail.lon) < 0.0001
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(12f),
            contentAlignment = Alignment.BottomCenter
        ) {
            androidx.compose.runtime.key(selectedPoiSpot) {
                com.gorite.cyclemap.ui.map.ThreeStageBottomSheet(
                    modifier = Modifier.onSizeChanged { placeDetailHeightPx?.intValue = it.height },
                    anchor = sheetAnchor,
                    onAnchorChange = { sheetAnchor = it },
                    footer = {
                        com.gorite.cyclemap.ui.map.PlaceDetailActionBar(
                            isFavorite = isFav,
                            onToggleFavorite = {
                                val existing = favoritesList.firstOrNull {
                                    kotlin.math.abs(it.latitude - currentDetail.lat) < 0.0001 &&
                                        kotlin.math.abs(it.longitude - currentDetail.lon) < 0.0001
                                }
                                if (existing != null) {
                                    favoritesManager.removeFavorite(existing.id)
                                } else {
                                    favoritesManager.addFavorite(
                                        FavoriteSpot(
                                            id = currentDetail.id,
                                            name = currentDetail.name,
                                            category = selectedPoiSpot?.category ?: currentDetail.category,
                                            latitude = currentDetail.lat,
                                            longitude = currentDetail.lon,
                                            address = currentDetail.address.takeIf { it.isNotBlank() },
                                        )
                                    )
                                }
                            },
                            onStartRoute = {
                                placeDetail = null
                                onSelectPoiSpot(null)
                                onCloseSpotPanel()
                                onRouteToSpot(GeoPoint(currentDetail.lat, currentDetail.lon))
                            },
                        )
                    },
                ) {
                    com.gorite.cyclemap.ui.map.PlaceDetailContent(
                        detail = currentDetail,
                        origin = origin,
                        onClose = {
                            placeDetail = null
                            onSelectPoiSpot(null)
                            onCloseSpotPanel()
                        }
                    )
                }
            }
        }
    }
}
