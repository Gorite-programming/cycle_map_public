package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import com.gorite.cyclemap.ui.components.*
import com.gorite.cyclemap.ui.effects.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.LifecycleOwner
import com.gorite.cyclemap.data.AddressDisplayController
import com.gorite.cyclemap.data.DownloadStatusManager
import com.gorite.cyclemap.data.MapSourceType
import com.gorite.cyclemap.data.Prefecture
import com.gorite.cyclemap.data.TileProgress
import com.gorite.cyclemap.data.OfflineDataDeleter
import com.gorite.cyclemap.data.SearchDbSelection
import com.gorite.cyclemap.data.SearchDbSelector
import com.gorite.cyclemap.ui.state.OfflineGraphUsageRegistry
import java.io.File
import org.json.JSONObject
import com.gorite.cyclemap.power.BatteryStatus
import com.gorite.cyclemap.routing.NavigationStats
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.speech.VoiceGuidanceMode
import com.gorite.cyclemap.speech.VoiceGuidanceNavigator
import com.gorite.cyclemap.tracking.AppLifecycleState
import com.gorite.cyclemap.ui.RoutingBenchmarkRequest
import com.gorite.cyclemap.ui.cycling.CyclingTab
import com.gorite.cyclemap.ui.cycling.ElevationSample
import com.gorite.cyclemap.ui.cycling.NearbySpot
import com.gorite.cyclemap.ui.cycling.PoiCategory
import com.gorite.cyclemap.ui.cycling.QuickSpotType
import com.gorite.cyclemap.ui.cycling.SpotQuickCategory
import com.gorite.cyclemap.ui.theme.ThemeMode
import org.maplibre.android.maps.MapLibreMap
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
internal fun MapModalsHost(
    context: Context,
    navigationRoute: List<com.gorite.cyclemap.routing.RoutePoint> = emptyList(),
    prefs: SharedPreferences,
    lifecycleOwner: LifecycleOwner,
    mapView: MapView?,
    vectorMap: MapLibreMap?,
    // Search effects props
    spotCategory: SpotQuickCategory?,
    showSpotPanel: Boolean,
    onCloseSpotPanel: () -> Unit,
    onSpotCategoryChange: (SpotQuickCategory) -> Unit,
    currentLocation: Location?,
    spotLoading: Boolean,
    onSpotLoadingChange: (Boolean) -> Unit,
    spotMessage: String?,
    onSpotMessageChange: (String?) -> Unit,
    spotResults: List<NearbySpot>,
    onSpotResultsChange: (List<NearbySpot>) -> Unit,
    activeQuickSpotType: QuickSpotType?,
    onActiveQuickSpotTypeChange: (QuickSpotType) -> Unit,
    showQuickSpotSheet: Boolean,
    onCloseQuickSpotSheet: () -> Unit,
    quickSpotLoading: Boolean,
    onQuickSpotLoadingChange: (Boolean) -> Unit,
    quickSpotMessage: String?,
    onQuickSpotMessageChange: (String?) -> Unit,
    quickSpotResults: List<NearbySpot>,
    onQuickSpotResultsChange: (List<NearbySpot>) -> Unit,
    selectedPoiSpot: NearbySpot?,
    onSelectPoiSpot: (NearbySpot?) -> Unit,
    // Panels host props
    navStats: NavigationStats?,
    onQuickSpotTypeSelectFromSpotPanel: (QuickSpotType) -> Unit,
    onRouteToSpot: (GeoPoint) -> Unit,
    showFavoritesOnlyInSheet: Boolean,
    onImportPlannedRoute: () -> Unit,
    // Settings dialogs props
    showRecordPanel: Boolean,
    isRecording: Boolean,
    gpxPointCount: Int,
    gpxStatusMessage: String?,
    navigationProgress: RouteProgress?,
    hasLocationPermission: Boolean,
    gpxTrackOverlay: Polyline?,
    onGpxTrackOverlayChanged: (Polyline?) -> Unit,
    onWarningMessage: (String) -> Unit,
    onCloseRecordPanel: () -> Unit,
    showCyclingSettings: Boolean,
    animationEnabled: Boolean,
    onAnimationEnabledChange: (Boolean) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    batteryStatus: BatteryStatus,
    autoRerouteEnabled: Boolean,
    onAutoRerouteEnabledChange: (Boolean) -> Unit,
    isHeadingUp: Boolean,
    onHeadingUpChange: (Boolean) -> Unit,
    orientationAnimator: MapOrientationAnimator,
    onUpdateNavArrow: () -> Unit,
    selectedLayer: MapLayer,
    onSelectedLayerChange: (MapLayer) -> Unit,
    poiVisible: Boolean,
    onPoiVisibleChange: (Boolean) -> Unit,
    poiSelected: Set<PoiCategory>,
    onPoiToggleCategory: (PoiCategory) -> Unit,
    /** 施設カテゴリの選択をまとめて置き換える (標準に戻す・すべて選択・すべて解除)。 */
    onPoiSetSelection: (Set<PoiCategory>) -> Unit = {},
    voiceGuidanceMode: VoiceGuidanceMode,
    onVoiceGuidanceModeChange: (VoiceGuidanceMode) -> Unit,
    voiceNavigator: VoiceGuidanceNavigator,
    onOpenPrefectureList: () -> Unit,
    onOpenLicense: () -> Unit,
    onOpenVersion: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onCloseCyclingSettings: () -> Unit,
    showLicense: Boolean,
    onDismissLicense: () -> Unit,
    showVersion: Boolean,
    onDismissVersion: () -> Unit,
    showDeveloperOptions: Boolean,
    permissionLauncher: ActivityResultLauncher<Array<String>>,
    isRoutingBenchmarkRunning: Boolean,
    benchmarkResult: String?,
    onRunRoutingBenchmark: (RoutingBenchmarkRequest) -> Unit,
    onCloseDeveloperOptions: () -> Unit,
    // Download dialogs props
    showAreaDownloadConfirmDialog: Boolean,
    selectedAreaBounds: BoundingBox?,
    isDownloading: Boolean,
    onStartAreaDownload: (BoundingBox, MapSourceType) -> Unit,
    onDismissAreaDownload: () -> Unit,
    showDestinationSearch: Boolean,
    onDismissDestinationSearch: () -> Unit,
    onSearchResultSelected: (com.gorite.cyclemap.contract.NearbySpot) -> Unit,
    pendingSelectedSpot: GeoPoint?,
    onAddWaypoint: (GeoPoint) -> Unit,
    onChangeDestination: (GeoPoint) -> Unit,
    onDismissPendingSpot: () -> Unit,
    showPrefectureListDialog: Boolean,
    onDismissPrefectureListDialog: () -> Unit,
    onPrefectureSelected: (Prefecture) -> Unit,
    showAlreadyDownloadedDialog: Boolean,
    selectedPrefectureForAction: Prefecture?,
    onRedownloadPrefecture: () -> Unit,
    onDismissAlreadyDownloaded: () -> Unit,
    showDownloadConfirmDialog: Boolean,
    onDownloadPrefectureGsi: (Prefecture) -> Unit,
    onDownloadPrefectureOsm: (Prefecture) -> Unit,
    onDismissDownloadConfirm: () -> Unit,
    // Lifecycle effects props
    followLocation: Boolean,
    locationMarker: Marker?,
    accuracyCircle: AccuracyCircleOverlay?,
    appLifecycleState: AppLifecycleState,
    effectiveAnimationEnabled: Boolean,
    navArrowCache: NavArrowCache,
    poiRefreshTick: Int,
    isNavigationActive: Boolean,
    addressController: AddressDisplayController,
    onLocationLabelChanged: (String) -> Unit,
    headingDegrees: Float,
) {
    MapSearchEffects(
        spotCategory = spotCategory,
        showSpotPanel = showSpotPanel,
        currentLocation = currentLocation,
        mapView = mapView,
        vectorMap = vectorMap,
        context = context,
        onSpotLoadingChange = onSpotLoadingChange,
        onSpotMessageChange = onSpotMessageChange,
        onSpotResultsChange = onSpotResultsChange,
        activeQuickSpotType = activeQuickSpotType,
        showQuickSpotSheet = showQuickSpotSheet,
        onQuickSpotLoadingChange = onQuickSpotLoadingChange,
        onQuickSpotMessageChange = onQuickSpotMessageChange,
        onQuickSpotResultsChange = onQuickSpotResultsChange,
        selectedPoiSpot = selectedPoiSpot,
    )

    MapPanelsHost(
        isNavigationActive = isNavigationActive,
        navigationRoute = navigationRoute,
        origin = currentLocation?.let { it.latitude to it.longitude },
        showSpotPanel = showSpotPanel,
        onCloseSpotPanel = onCloseSpotPanel,
        spotCategory = spotCategory,
        onSpotCategoryChange = onSpotCategoryChange,
        spotLoading = spotLoading,
        spotMessage = spotMessage,
        spotResults = spotResults,
        onQuickSpotTypeSelectFromSpotPanel = onQuickSpotTypeSelectFromSpotPanel,
        onRouteToSpot = onRouteToSpot,
        showFavoritesOnlyInSheet = showFavoritesOnlyInSheet,
        showQuickSpotSheet = showQuickSpotSheet,
        onCloseQuickSpotSheet = onCloseQuickSpotSheet,
        activeQuickSpotType = activeQuickSpotType,
        onActiveQuickSpotTypeChange = onActiveQuickSpotTypeChange,
        quickSpotLoading = quickSpotLoading,
        quickSpotMessage = quickSpotMessage,
        quickSpotResults = quickSpotResults,
        onSelectPoiSpot = onSelectPoiSpot,
        mapView = mapView,
        vectorMap = vectorMap,
        selectedPoiSpot = selectedPoiSpot,
    )

    MapSettingsDialogs(
        context = context,
        prefs = prefs,
        showRecordPanel = showRecordPanel,
        isRecording = isRecording,
        gpxPointCount = gpxPointCount,
        gpxStatusMessage = gpxStatusMessage,
        navStats = navStats,
        navigationProgress = navigationProgress,
        hasLocationPermission = hasLocationPermission,
        mapView = mapView,
        vectorMap = vectorMap,
        gpxTrackOverlay = gpxTrackOverlay,
        onGpxTrackOverlayChanged = onGpxTrackOverlayChanged,
        onWarningMessage = onWarningMessage,
        onCloseRecordPanel = onCloseRecordPanel,
        onImportPlannedRoute = onImportPlannedRoute,
        showCyclingSettings = showCyclingSettings,
        animationEnabled = animationEnabled,
        onAnimationEnabledChange = onAnimationEnabledChange,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        batteryStatus = batteryStatus,
        autoRerouteEnabled = autoRerouteEnabled,
        onAutoRerouteEnabledChange = onAutoRerouteEnabledChange,
        isHeadingUp = isHeadingUp,
        onHeadingUpChange = onHeadingUpChange,
        orientationAnimator = orientationAnimator,
        onUpdateNavArrow = onUpdateNavArrow,
        selectedLayer = selectedLayer,
        onSelectedLayerChange = onSelectedLayerChange,
        poiVisible = poiVisible,
        onPoiVisibleChange = onPoiVisibleChange,
        poiSelected = poiSelected,
        onPoiToggleCategory = onPoiToggleCategory,
        onPoiSetSelection = onPoiSetSelection,
        voiceGuidanceMode = voiceGuidanceMode,
        onVoiceGuidanceModeChange = onVoiceGuidanceModeChange,
        voiceNavigator = voiceNavigator,
        onOpenPrefectureList = onOpenPrefectureList,
        onOpenLicense = onOpenLicense,
        onOpenVersion = onOpenVersion,
        onOpenDeveloperOptions = onOpenDeveloperOptions,
        onCloseCyclingSettings = onCloseCyclingSettings,
        showLicense = showLicense,
        onDismissLicense = onDismissLicense,
        showVersion = showVersion,
        onDismissVersion = onDismissVersion,
        showDeveloperOptions = showDeveloperOptions,
        permissionLauncher = permissionLauncher,
        isRoutingBenchmarkRunning = isRoutingBenchmarkRunning,
        benchmarkResult = benchmarkResult,
        onRunRoutingBenchmark = onRunRoutingBenchmark,
        onCloseDeveloperOptions = onCloseDeveloperOptions,
    )

    if (showDestinationSearch) {
        DestinationSearchDialog(
            context = context,
            mapCenter = vectorMap?.cameraPosition?.target?.let { it.latitude to it.longitude }
                ?: mapView?.mapCenter?.let { it.latitude to it.longitude },
            liveLocation = currentLocation?.let { it.latitude to it.longitude },
            onDismiss = onDismissDestinationSearch,
            onResultSelected = { result ->
                onSearchResultSelected(
                    com.gorite.cyclemap.contract.NearbySpot(
                        name = result.name,
                        category = result.category,
                        latitude = result.latitude,
                        longitude = result.longitude,
                        distanceM = result.distanceMeters ?: 0.0,
                    ),
                )
            },
        )
    }

    MapDownloadDialogs(
        context = context,
        offlineDataUsage = {
            val graph = OfflineGraphUsageRegistry.entries[context]
            val dataDir = cycleMapDataDir(context)
            val centers = listOfNotNull(
                currentLocation?.let { it.latitude to it.longitude },
                vectorMap?.cameraPosition?.target?.let { it.latitude to it.longitude },
                (mapView?.mapCenter as? GeoPoint)?.let { it.latitude to it.longitude },
            )
            val searchNames = centers.mapNotNull { (lat, lon) ->
                (SearchDbSelector.select(lat, lon) as? SearchDbSelection.Available)?.let {
                    SearchDbSelector.resolveDbFile(dataDir, it.fileName)?.name
                }
            }.toSet()
            val sources = vectorMap?.style?.json?.let { JSONObject(it).optJSONObject("sources") }
            val tileNames = buildSet {
                sources?.keys()?.forEach { key ->
                    val source = sources?.optJSONObject(key)
                    val url = source?.optString("url").orEmpty()
                    if (url.startsWith("pmtiles://file://")) add(File(url.removePrefix("pmtiles://file://")).name)
                }
            }
            OfflineDataDeleter.Usage(
                loadedGraphName = graph?.loadedName?.value,
                searchDbName = if (centers.isEmpty()) SearchDbSelector.findFirstAvailableDb(dataDir)?.name else null,
                // 検索は現在地と地図中心の両方から起動しうるため、双方を保護する。
                searchDbNames = searchNames,
                tileNames = tileNames,
                busy = isNavigationActive || graph == null || graph.switching.value || graph.calculating.value ||
                    spotLoading || quickSpotLoading || showDestinationSearch || isDownloading || isRoutingBenchmarkRunning,
            )
        },
        showAreaDownloadConfirmDialog = showAreaDownloadConfirmDialog,
        selectedAreaBounds = selectedAreaBounds,
        isDownloading = isDownloading,
        onStartAreaDownload = onStartAreaDownload,
        onDismissAreaDownload = onDismissAreaDownload,
        showDestinationSearch = false, // Destination search is hosted above with the map camera center.
        currentLocation = currentLocation,
        onDismissDestinationSearch = onDismissDestinationSearch,
        onSearchResultSelected = onSearchResultSelected,
        pendingSelectedSpot = pendingSelectedSpot,
        onAddWaypoint = onAddWaypoint,
        onChangeDestination = onChangeDestination,
        onDismissPendingSpot = onDismissPendingSpot,
        showPrefectureListDialog = showPrefectureListDialog,
        onDismissPrefectureListDialog = onDismissPrefectureListDialog,
        onPrefectureSelected = onPrefectureSelected,
        showAlreadyDownloadedDialog = showAlreadyDownloadedDialog,
        selectedPrefectureForAction = selectedPrefectureForAction,
        onRedownloadPrefecture = onRedownloadPrefecture,
        onDismissAlreadyDownloaded = onDismissAlreadyDownloaded,
        showDownloadConfirmDialog = showDownloadConfirmDialog,
        onDownloadPrefectureGsi = onDownloadPrefectureGsi,
        onDownloadPrefectureOsm = onDownloadPrefectureOsm,
        onDismissDownloadConfirm = onDismissDownloadConfirm,
    )

    MapLifecycleEffects(
        mapView = mapView,
        vectorMap = vectorMap,
        lifecycleOwner = lifecycleOwner,
        context = context,
        currentLocation = currentLocation,
        followLocation = followLocation,
        locationMarker = locationMarker,
        accuracyCircle = accuracyCircle,
        appLifecycleState = appLifecycleState,
        effectiveAnimationEnabled = effectiveAnimationEnabled,
        navArrowCache = navArrowCache,
        onUpdateNavArrow = onUpdateNavArrow,
        poiVisible = poiVisible,
        poiSelected = poiSelected,
        poiRefreshTick = poiRefreshTick,
        isNavigationActive = isNavigationActive,
        onSelectPoiSpot = onSelectPoiSpot,
        addressController = addressController,
        onLocationLabelChanged = onLocationLabelChanged,
        isHeadingUp = isHeadingUp,
        headingDegrees = headingDegrees,
        orientationAnimator = orientationAnimator,
        selectedLayer = selectedLayer,
    )
}
