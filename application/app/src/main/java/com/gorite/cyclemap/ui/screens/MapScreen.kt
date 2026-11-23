package com.gorite.cyclemap.ui.screens

import com.gorite.cyclemap.ui.mapview.animateCameraExclusive
import com.gorite.cyclemap.ui.mapview.ViewCenterSaveThrottle
import com.gorite.cyclemap.ui.mapview.mapFrameIntervalNanos
import com.gorite.cyclemap.ui.mapview.mapMaxFps
import com.gorite.cyclemap.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.concurrent.read
import com.gorite.cyclemap.utils.*
import android.Manifest
import android.os.SystemClock
import android.util.Log
import com.gorite.cyclemap.ui.layout.*
import com.gorite.cyclemap.ui.state.*
import com.gorite.cyclemap.ui.effects.*
import com.gorite.cyclemap.navigation.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gorite.cyclemap.routing.roadCandidatesNear
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.routing.calculateRouteProgress
import com.gorite.cyclemap.ui.RoutingBenchmarkRequest
import com.gorite.cyclemap.ui.theme.ThemeMode
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.util.concurrent.atomic.AtomicLong
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// MapScreen Composable
// ---------------------------------------------------------------------------

@Composable
internal fun MapScreen(
    modifier: Modifier = Modifier,
    systemInsets: PaddingValues = PaddingValues(),
    themeMode: ThemeMode = ThemeMode.AUTO,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    isDarkTheme: Boolean = false,
    onLocationUpdate: ((android.location.Location) -> Unit)? = null,
    onNavigationStateChanged: (Boolean) -> Unit = {},
) {
    val state = rememberMapScreenState(
        onNavigationStateChanged = onNavigationStateChanged,
    )
    LaunchedEffect(state.voiceGuidanceModeState.value) {
        val muted = state.voiceGuidanceModeState.value == com.gorite.cyclemap.speech.VoiceGuidanceMode.OFF
        if (state.viewModel.uiState.value.isMuted != muted) {
            state.viewModel.onIntent(com.gorite.cyclemap.ui.map.MapUiIntent.ToggleMute)
        }
    }
    val arrivalRide = remember { mutableStateOf<com.gorite.cyclemap.tracking.RideSnapshot?>(null) }
    val navigationEndPrompt = rememberSaveable(
        saver = Saver(
            save = { it.didStartNavigation },
            restore = { NavigationEndPromptTracker(it) },
        ),
    ) { NavigationEndPromptTracker() }
    val showContinueRecordingConfirm = remember { mutableStateOf(false) }
    val waitingDestination = remember { mutableStateOf<GeoPoint?>(null) }
    val restoredDestination = remember { mutableStateOf<GeoPoint?>(null) }
    val manualRestoreOrigin = remember { mutableStateOf<GeoPoint?>(null) }
    val routeExecutionGeneration = remember { AtomicLong(0L) }
    val pendingRouteGpx = remember { mutableStateOf<String?>(null) }
    val exportRouteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val xml = pendingRouteGpx.value
        pendingRouteGpx.value = null
        if (uri != null && xml != null) {
            state.scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    state.context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
                        ?: error("保存先を開けませんでした")
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        state.context,
                        if (result.isSuccess) "GPXを書き出しました" else "GPXを書き出せませんでした",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    val importRouteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            state.scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    state.context.contentResolver.openInputStream(uri)?.use { com.gorite.cyclemap.tracking.GpxParser.parseRoutePoints(it) }
                        ?: error("ファイルを開けませんでした")
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { route ->
                        routeExecutionGeneration.incrementAndGet()
                        state.routeJobRef.getAndSet(null)?.cancel()
                        state.inFlightRouteRef.set(null)
                        state.pendingRouteRef.set(null)
                        state.viewModel.onIntent(com.gorite.cyclemap.ui.map.MapUiIntent.StopNavigation)
                        state.routeOverlayState.value?.let { overlay -> state.mapViewState.value?.overlays?.remove(overlay) }
                        state.destinationMarkerState.value?.let { marker -> state.mapViewState.value?.overlays?.remove(marker) }
                        state.waypointMarkersState.value.forEach { marker -> state.mapViewState.value?.overlays?.remove(marker) }
                        var distance = 0.0
                        for (index in 1 until route.size) {
                            val previous = route[index - 1]
                            val current = route[index]
                            distance += com.gorite.cyclemap.utils.haversineMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
                        }
                        navigationEndPrompt.onRouteReplaced()
                        state.routeOverlayState.value = null
                        state.navigationRouteState.value = route
                        state.navigationManeuversState.value = com.gorite.cyclemap.routing.extractManeuvers(route)
                        state.navigationInstructionsState.value = emptyList()
                        state.navigationProgressState.value = null
                        state.navStatsState.value = null
                        state.routeSummaryState.value = RouteSummary(distance, route.size - 1)
                        state.destinationState.value = org.osmdroid.util.GeoPoint(route.last().latitude, route.last().longitude)
                        state.destinationMarkerState.value = null
                        state.waypointsState.value = emptyList()
                        state.waypointMarkersState.value = emptyList()
                        state.calculatedRouteOptionsState.value = emptyList()
                        state.selectedRouteIndexState.value = 0
                        state.crossRouteElevationState.value = null
                        state.realElevProfileState.value = emptyList()
                        state.isNavigationActiveState.value = false
                        state.isCalculatingRouteState.value = false
                        state.isReroutingState.value = false
                        state.isArrivedState.value = false
                        state.followLocationState.value = false
                        android.widget.Toast.makeText(state.context, "GPXルートを読み込みました", android.widget.Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        android.widget.Toast.makeText(
                            state.context,
                            "GPXを読み込めませんでした: ${error.message ?: "ファイルを確認してください"}",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
    val routePreviewSheetHeightPx = remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val orientationAnimator = remember {
        MapOrientationAnimator(
            getMapView = { state.mapViewState.value },
            getVectorMap = { state.vectorMapState.value },
            isVectorActive = { state.selectedLayerState.value == com.gorite.cyclemap.utils.MapLayer.VECTOR_LIBERTY },
            minFrameIntervalNanos = {
                if (state.selectedLayerState.value == MapLayer.VECTOR_LIBERTY &&
                    state.vectorMapState.value != null && state.mapViewState.value == null
                ) {
                    mapFrameIntervalNanos(mapMaxFps(
                        navigationActive = state.isNavigationActiveState.value,
                        followLocation = state.followLocationState.value && state.selectedPoiSpotState.value == null,
                        powerSave = state.batteryMonitor.status.value.isLowPower,
                    ))
                } else {
                    0L
                }
            },
            onOrientationUpdated = { state.updateNavArrow() },
        )
    }

    val latestLocation by rememberUpdatedState(state.currentLocationState.value)
    val latestRouteOverlay by rememberUpdatedState(state.routeOverlayState.value)
    val latestDestMarker by rememberUpdatedState(state.destinationMarkerState.value)
    val latestStartMarker by rememberUpdatedState(state.startMarkerState.value)
    val latestWaypoints by rememberUpdatedState(state.waypointsState.value)
    val latestWaypointMarkers by rememberUpdatedState(state.waypointMarkersState.value)
    val latestRoutePreference by rememberUpdatedState(state.selectedRoutePreferenceState.value)
    val latestMapView by rememberUpdatedState(state.mapViewState.value)

    val routeExecutionContext = RouteExecutionContext(
        context = state.context,
        scope = state.scope,
        routeJobRef = state.routeJobRef,
        routeExecutionGeneration = routeExecutionGeneration,
        inFlightRouteRef = state.inFlightRouteRef,
        graphLock = state.graphLock,
        pendingRouteRef = state.pendingRouteRef,
        // rememberUpdatedState 経由だと描き直すまで古い値のままで、読み込み直後の計算がグラフ無しと判定されていた。状態を直接読む。
        getGraph = { state.mappedGraphState.value },
        getLoadedGraphName = { state.loadedGraphNameState.value },
        isSwitchingGraph = { state.isSwitchingGraphState.value },
        onSwitchGraph = {
            state.switchRef.get()?.invoke(it)
        },
        getMapView = { latestMapView },
        getDestMarker = { latestDestMarker },
        getStartMarker = { latestStartMarker },
        getWaypointMarkers = { latestWaypointMarkers },
        getRouteOverlay = { latestRouteOverlay },
        getWaypoints = { latestWaypoints },
        getRoutePreference = { latestRoutePreference },
        offRouteDetector = state.offRouteDetector,
        onDestinationChanged = { state.destinationState.value = it },
        onWaypointsChanged = { state.waypointsState.value = it },
        onStartMarkerChanged = { state.startMarkerState.value = it },
        onDestMarkerChanged = { state.destinationMarkerState.value = it },
        onWaypointMarkersChanged = { state.waypointMarkersState.value = it },
        onCalculatingRouteChanged = { state.isCalculatingRouteState.value = it },
        onReroutingChanged = { state.isReroutingState.value = it },
        onLongCalculationNoticeCleared = {
            if (state.warningMessageState.value == LONG_CROSS_PREFECTURE_CALCULATION_MESSAGE) state.warningMessageState.value = null
        },
        onWarningMessageChanged = { state.warningMessageState.value = it },
        onRouteCalculated = { overlay, route, maneuvers, instructions, summary, isReroute, crossElevation ->
            navigationEndPrompt.onRouteReplaced(isReroute = isReroute)
            state.routeOverlayState.value = overlay
            state.navigationRouteState.value = route
            state.navigationManeuversState.value = maneuvers
            state.navigationInstructionsState.value = instructions
            state.navigationProgressState.value = null
            state.navStatsState.value = null
            state.offRouteDetector.reset()
            // 主経路の表示時点では代替ルートは未計算 (reroute でも従来どおり候補を空にして主線に戻す)
            state.calculatedRouteOptionsState.value = emptyList()
            state.selectedRouteIndexState.value = 0
            if (!isReroute) {
                state.isNavigationActiveState.value = false
                state.isArrivedState.value = false
                state.rerouteCountState.intValue = 0
                state.rerouteLimitWarnedState.value = false
                state.lastRerouteElapsedMsState.value = 0L
                state.offRouteDetector.reset()
            } else {
                state.voiceNavigator.onRerouted()
                state.navStartElapsedMsState.value = SystemClock.elapsedRealtime()
                state.rerouteCountState.intValue++
            }
            state.routeSummaryState.value = summary
            // 通常経路・fallback・reroute では null を受け取り、以前の県またぎ標高を破棄する。
            state.crossRouteElevationState.value = crossElevation
            if (state.warningMessageState.value?.contains("グラフに切替中です") == true) {
                state.warningMessageState.value = null
            }
        },
        onRouteOptionsCalculated = { options ->
            // 遅延した候補が案内中の一本表示を復活させない。
            if (!state.isNavigationActiveState.value) {
                state.calculatedRouteOptionsState.value = options
            }
        },
    )

    val runRouteCalculation: (start: GeoPoint, goal: GeoPoint, isReroute: Boolean, waypointsOverride: List<GeoPoint>?, preferenceOverride: RoutePreference?) -> Unit = { start, goal, isReroute, waypointsOverride, preferenceOverride ->
        executeRouteCalculation(routeExecutionContext, start, goal, isReroute, waypointsOverride, preferenceOverride)
    }
    val retryRouteCalculation: (RouteRequest, Boolean) -> Unit = { request, retriedAfterSwitch ->
        executeRouteCalculation(
            routeExecutionContext,
            request.start,
            request.goal,
            request.isReroute,
            request.waypoints,
            request.preference,
            retriedAfterSwitch,
        )
    }

    val waitForStartPoint: (GeoPoint) -> Unit = { point ->
        waitingDestination.value = point
        if (shouldShowWaitingDestination(hasDisplayedRoute = state.navigationRouteState.value.isNotEmpty())) {
            state.destinationState.value = point
        }
        state.warningMessageState.value = WAITING_FOR_LOCATION
    }
    val startRouteToDestination: (GeoPoint) -> Unit = { point ->
        val loc = state.currentLocationState.value
        val start = state.startMarkerState.value?.position ?: loc?.let { GeoPoint(it.latitude, it.longitude) }
        if (start == null) {
            waitForStartPoint(point)
        } else {
            waitingDestination.value = null
            runRouteCalculation(start, point, false, null, null)
        }
    }
    LaunchedEffect(state.currentLocationState.value, state.startMarkerState.value) {
        val goal = waitingDestination.value ?: return@LaunchedEffect
        if (state.currentLocationState.value != null || state.startMarkerState.value != null) {
            state.warningMessageState.value = null
            startRouteToDestination(goal)
        }
    }

    val handleSpotSelected: (GeoPoint) -> Unit = { point ->
        val waiting = waitingDestination.value
        if (restoredDestination.value != null) {
            manualRestoreOrigin.value = point
        } else if (waiting != null) {
            waitingDestination.value = null
            state.warningMessageState.value = null
            runRouteCalculation(point, waiting, false, null, null)
        } else if (state.destinationState.value != null) {
            state.pendingSelectedSpotState.value = point
        } else {
            startRouteToDestination(point)
        }
    }

    val rideSessionStore = remember { com.gorite.cyclemap.tracking.RideSessionStore(state.prefs) }
    val rideSession = remember { rideSessionStore.restore() ?: com.gorite.cyclemap.tracking.RideSession() }
    MapGraphEffects(
        context = state.context,
        scope = state.scope,
        routeJobRef = state.routeJobRef,
        inFlightRouteRef = state.inFlightRouteRef,
        routeExecutionGeneration = routeExecutionGeneration,
        graphLock = state.graphLock,
        pendingRouteRef = state.pendingRouteRef,
        switchRef = state.switchRef,
        mappedGraph = state.mappedGraphState.value,
        onMappedGraphChanged = { state.mappedGraphState.value = it },
        loadedGraphName = state.loadedGraphNameState.value,
        onLoadedGraphNameChanged = { state.loadedGraphNameState.value = it },
        isSwitchingGraph = state.isSwitchingGraphState.value,
        onSwitchingGraphChanged = { state.isSwitchingGraphState.value = it },
        onCalculatingRouteChanged = { state.isCalculatingRouteState.value = it },
        onReroutingChanged = { state.isReroutingState.value = it },
        onLongCalculationNoticeCleared = {
            if (state.warningMessageState.value == LONG_CROSS_PREFECTURE_CALCULATION_MESSAGE) state.warningMessageState.value = null
        },
        onWarningMessageChanged = { state.warningMessageState.value = it },
        currentLocation = state.currentLocationState.value,
        onLocationUpdate = onLocationUpdate,
        runRouteCalculation = runRouteCalculation,
        retryRouteCalculation = retryRouteCalculation,
        onGpxRecordingStarted = {
            state.isRecordingState.value = true
            state.gpxPointCountState.value = 0
            state.gpxNotificationTextState.value = "記録を開始しました"
        },
        onGpxRecordingEmpty = {
            rideSession.reset()
            rideSessionStore.clear()
            state.isRecordingState.value = false
            state.gpxPointCountState.value = 0
            state.gpxNotificationTextState.value = "位置情報を取得できなかったため記録しませんでした"
        },
        onGpxRecordingSaved = { points, fileName ->
            rideSession.reset()
            rideSessionStore.clear()
            state.isRecordingState.value = false
            state.gpxPointCountState.value = points
            state.gpxNotificationTextState.value = "GPX保存完了 ($points 点): $fileName"
        },
    )

    // ベクター→ラスター切替時のルート線・マーカー再構築 (HIGH-03 補完)
    val curMapView = state.mapViewState.value
    val curNavRoute = state.navigationRouteState.value
    val curDest = state.destinationState.value
    val curWaypoints = state.waypointsState.value
    val curArrived = state.isArrivedState.value
    LaunchedEffect(curMapView, curNavRoute, curDest, curWaypoints, curArrived) {
        val view = curMapView ?: return@LaunchedEffect

        // 到着後は目的地ピンだけ消す (ルート線は「完了」まで残す)。再構築もしない。
        if (curArrived) {
            state.destinationMarkerState.value?.let { view.overlays.remove(it); view.invalidate() }
            state.destinationMarkerState.value = null
        }

        // 目的地の再構築
        if (!curArrived && curDest != null && state.destinationMarkerState.value == null) {
            val destM = org.osmdroid.views.overlay.Marker(view).apply {
                position = curDest
                title = "目的地"
                icon = androidx.core.content.ContextCompat.getDrawable(state.context, R.drawable.ic_destination)
                setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
            }
            view.overlays.add(destM)
            state.destinationMarkerState.value = destM
        }

        // 経由地の再構築
        if (curWaypoints.isNotEmpty() && state.waypointMarkersState.value.isEmpty()) {
            val wpMarkers = curWaypoints.mapIndexed { idx, wp ->
                org.osmdroid.views.overlay.Marker(view).apply {
                    position = wp
                    title = "経由地 ${idx + 1}"
                    icon = waypointDrawable(state.context, idx + 1)
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                }
            }
            wpMarkers.forEach { view.overlays.add(it) }
            state.waypointMarkersState.value = wpMarkers
        }

        // ルート線の再構築
        if (curNavRoute.isNotEmpty()) {
            val polyline = state.routeOverlayState.value ?: org.osmdroid.views.overlay.Polyline(view).apply {
                outlinePaint.color = android.graphics.Color.parseColor("#333333")
                outlinePaint.strokeWidth = 24f
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.isAntiAlias = true
            }
            polyline.setPoints(curNavRoute.map { GeoPoint(it.latitude, it.longitude) })
            if (!view.overlays.contains(polyline)) view.overlays.add(polyline)
            state.routeOverlayState.value = polyline
        }
        view.invalidate()
    }

    LaunchedEffect(
        state.navigationRouteState.value,
        state.mapViewState.value,
        state.vectorMapState.value,
        state.isNavigationActiveState.value,
        screenHeightPx,
        routePreviewSheetHeightPx.intValue,
    ) {
        val navRoute = state.navigationRouteState.value
        val sheetHeightPx = routePreviewSheetHeightPx.intValue
        if (navRoute.isEmpty() || state.isNavigationActiveState.value || sheetHeightPx == 0) return@LaunchedEffect
        // Preview中はGPS追従を止めて、全ルートのカメラフィットを維持する。
        state.followLocationState.value = false
        withFrameNanos { }
        val expanded = expandBoundsToMinSpan(
            minLat = navRoute.minOf { it.latitude },
            maxLat = navRoute.maxOf { it.latitude },
            minLon = navRoute.minOf { it.longitude },
            maxLon = navRoute.maxOf { it.longitude },
        )
        state.mapViewState.value?.let { view ->
            val bounds = BoundingBox(expanded.maxLat, expanded.maxLon, expanded.minLat, expanded.minLon)
            val padding = previewPaddingPx(view.height.takeIf { it > 0 } ?: screenHeightPx, density.density, sheetHeightPx)
            val visibleHeight = view.height - padding.top - padding.bottom
            val visibleWidth = view.width - 2 * padding.side
            if (visibleHeight > 0 && visibleWidth > 0) {
                val zoom = org.osmdroid.views.MapView.getTileSystem()
                    .getBoundingBoxZoom(bounds, visibleWidth, visibleHeight)
                    .coerceIn(view.minZoomLevel, view.maxZoomLevel)
                val projection = org.osmdroid.views.Projection(
                    zoom, view.width, view.height, bounds.centerWithDateLine, view.mapOrientation,
                    view.isHorizontalMapRepetitionEnabled, view.isVerticalMapRepetitionEnabled, 0, 0,
                )
                // カメラの中心をシート側へずらすと、経路は地図の見えている領域の中央に来る。
                val center = projection.fromPixels(view.width / 2, (view.height + padding.bottom - padding.top) / 2)
                view.controller.animateTo(center, zoom, 500L)
            } else {
                view.zoomToBoundingBox(bounds, true, 64)
            }
        }
        state.vectorMapState.value?.let { vMap ->
            val padding = previewPaddingPx(screenHeightPx, density.density, sheetHeightPx)
            val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
            boundsBuilder.include(org.maplibre.android.geometry.LatLng(expanded.minLat, expanded.minLon))
            boundsBuilder.include(org.maplibre.android.geometry.LatLng(expanded.maxLat, expanded.maxLon))
            val latLngBounds = boundsBuilder.build()
            // 地図のタイルはズーム 6 未満だと粗い陸地ポリゴンだけになるため、長距離ルートでもズーム 6 より引かない。
            // 収まりきらない分は画面の外に出る (ルート全体が見えることより、道路・地名が読めることを優先)。
            val fitted = vMap.getCameraForLatLngBounds(latLngBounds, intArrayOf(padding.side, padding.top, padding.side, padding.bottom))
            if (fitted != null && fitted.zoom < PREVIEW_MIN_ZOOM) {
                vMap.animateCamera(
                    // パディング (下のパネルの分) を保つため、位置ごと引き継いでズームだけ替える
                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder(fitted).zoom(PREVIEW_MIN_ZOOM).build(),
                    ),
                )
            } else {
                vMap.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(
                        latLngBounds,
                        padding.side,
                        padding.top,
                        padding.side,
                        padding.bottom,
                    ),
                )
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        state.hasLocationPermissionState.value = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // 表示モード (通常 / サイコン) と、サイコンの走行統計。
    val displayModeState = remember {
        androidx.compose.runtime.mutableStateOf(
            com.gorite.cyclemap.ui.state.displayModeFromPref(state.prefs.getString(com.gorite.cyclemap.ui.state.PREF_DISPLAY_MODE, null)),
        )
    }
    val displayModeController = remember {
        com.gorite.cyclemap.ui.state.DisplayModeController(displayModeState) { mode ->
            displayModeState.value = mode
            state.prefs.edit().putString(com.gorite.cyclemap.ui.state.PREF_DISPLAY_MODE, mode.name).apply()
        }
    }
    // サイコンの走行統計。落ちても値が戻るよう保存し (CycleComputerHost が定期的に保存)、起動時に 30 分以内のものを一時停止で戻す。
    // サイコンの間は地図を作らないので、破棄された地図への参照を捨てる (戻ると作り直された地図が登録し直す)
    LaunchedEffect(displayModeState.value) {
        if (displayModeState.value == com.gorite.cyclemap.ui.state.DisplayMode.CYCLE_COMPUTER) {
            state.vectorMapState.value = null
        }
    }
    // 道路のルール (自動車専用・逆走など) の通知。位置の更新のたびに、近くの辺と照合する (分からないときは何も出さない)
    val roadRuleEnabledState = remember {
        androidx.compose.runtime.mutableStateOf(state.prefs.getBoolean(com.gorite.cyclemap.ui.state.PREF_ROAD_RULE_ENABLED, true))
    }
    val roadRuleKindState = remember { androidx.compose.runtime.mutableStateOf<com.gorite.cyclemap.routing.RoadRuleKind?>(null) }
    val roadRuleMatcher = remember { com.gorite.cyclemap.routing.RoadRuleMatcher() }
    val roadRuleController = remember {
        com.gorite.cyclemap.ui.state.RoadRuleController(
            enabled = roadRuleEnabledState,
            setEnabled = { on ->
                roadRuleEnabledState.value = on
                state.prefs.edit().putBoolean(com.gorite.cyclemap.ui.state.PREF_ROAD_RULE_ENABLED, on).apply()
                if (!on) { roadRuleMatcher.reset(); roadRuleKindState.value = null }
            },
            kind = roadRuleKindState,
        )
    }
    // 経路用グラフから除外した道 (自動車専用など) のサイドカー (<県>.bikerules)。無ければ使わない (任意の成果物)
    val excludedRoadsState = remember { androidx.compose.runtime.mutableStateOf<com.gorite.cyclemap.routing.ExcludedRoadIndex?>(null) }
    LaunchedEffect(state.mappedGraphState.value) {
        roadRuleMatcher.reset()
        roadRuleKindState.value = null
    }
    LaunchedEffect(state.loadedGraphNameState.value) {
        val graphName = state.loadedGraphNameState.value
        excludedRoadsState.value = if (graphName == null) null else withContext(Dispatchers.IO) {
            val dir = cycleMapDataDir(state.context)
            val base = java.io.File(graphName).name.removeSuffix(".graph")
            listOf(base, base.replaceFirstChar { it.uppercase() }, base.lowercase())
                .distinct()
                .firstNotNullOfOrNull { com.gorite.cyclemap.routing.ExcludedRoadIndex.load(java.io.File(dir, "$it.bikerules")) }
        }
    }
    val trackingService = remember { mutableStateOf<com.gorite.cyclemap.tracking.LocationTrackingService?>(null) }
    val showRecovery = remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(state.context) {
        val connection = com.gorite.cyclemap.tracking.locationServiceConnection { trackingService.value = it }
        com.gorite.cyclemap.tracking.LocationTrackingService.bind(state.context, connection)
        onDispose { state.context.unbindService(connection) }
    }
    LaunchedEffect(trackingService.value) {
        val service = trackingService.value
        if (service == null) showRecovery.value = false
        else service.recoveryPendingState.collect { showRecovery.value = it }
    }
    if (showRecovery.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { androidx.compose.material3.Text("前回の走行記録があります") },
            text = { androidx.compose.material3.Text("前回の記録を続けるか、GPXに保存して終了できます。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.gorite.cyclemap.tracking.LocationTrackingService.resumeRecording(state.context)
                    rideSession.start()
                    showRecovery.value = false
                }) { androidx.compose.material3.Text("続ける") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    com.gorite.cyclemap.tracking.LocationTrackingService.stopRecording(state.context)
                    rideSession.pause()
                    rideSessionStore.save(rideSession)
                    showRecovery.value = false
                }) { androidx.compose.material3.Text("保存して終える") }
            },
        )
    }
    LaunchedEffect(state.isRecordingState.value) {
        if (state.isRecordingState.value) rideSession.start()
        else if (rideSession.snapshot(System.currentTimeMillis()).state != com.gorite.cyclemap.tracking.RideState.IDLE) {
            rideSession.pause()
            rideSessionStore.save(rideSession)
        }
    }
    // Restore a viewed position independently of GPS; it must never become a routing origin.
    val initialMapCenter = remember {
        val savedLat = state.prefs.getString("view_lat", null)?.toDoubleOrNull()
        val savedLon = state.prefs.getString("view_lon", null)?.toDoubleOrNull()
        val graphName = com.gorite.cyclemap.data.RoutingGraphSelector.findFirstAvailableGraph(cycleMapDataDir(state.context))?.first?.name
        val pref = com.gorite.cyclemap.data.PrefectureData.ALL.firstOrNull {
            graphName?.substringBefore('.')?.equals(it.id, ignoreCase = true) == true
        }
        if (savedLat != null && savedLon != null && savedLat.isFinite() && savedLon.isFinite()) savedLat to savedLon
        else pref?.bounds?.let { (it.latNorth + it.latSouth) / 2 to (it.lonEast + it.lonWest) / 2 }
            ?: (36.0 to 138.0)
    }
    val viewedCenter = remember { java.util.concurrent.atomic.AtomicReference(initialMapCenter) }
    val centerSaveThrottle = remember { ViewCenterSaveThrottle() }
    val vectorCenterReady = remember(state.vectorMapState.value) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val rasterCenterReady = remember(state.mapViewState.value) { java.util.concurrent.atomic.AtomicBoolean(false) }
    LaunchedEffect(state.mapViewState.value) {
        val map = state.mapViewState.value ?: return@LaunchedEffect
        if (state.currentLocationState.value == null && state.navigationRouteState.value.isEmpty()) {
            val center = viewedCenter.get()
            map.controller.setCenter(GeoPoint(center.first, center.second))
        }
        rasterCenterReady.set(true)
    }
    LaunchedEffect(state.vectorMapState.value) {
        val map = state.vectorMapState.value ?: return@LaunchedEffect
        withFrameNanos { }
        if (state.currentLocationState.value == null && state.navigationRouteState.value.isEmpty()) {
            val center = viewedCenter.get()
            map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                org.maplibre.android.geometry.LatLng(center.first, center.second)))
        }
        vectorCenterReady.set(true)
    }
    androidx.compose.runtime.DisposableEffect(state.vectorMapState.value, state.mapViewState.value) {
        val vector = state.vectorMapState.value
        val raster = state.mapViewState.value
        var disposed = false
        fun persistCenter(center: Pair<Double, Double>) {
            val (lat, lon) = center
            state.prefs.edit().putString("view_lat", lat.toString()).putString("view_lon", lon.toString()).apply()
        }
        fun save(lat: Double, lon: Double) {
            if (lat.isFinite() && lon.isFinite()) {
                val center = lat to lon
                viewedCenter.set(center)
                centerSaveThrottle.onCenterChanged(center, SystemClock.elapsedRealtime())?.let(::persistCenter)
            }
        }
        val listener = org.maplibre.android.maps.MapLibreMap.OnCameraIdleListener {
            if (vectorCenterReady.get()) vector?.cameraPosition?.target?.let { save(it.latitude, it.longitude) }
        }
        val rasterListener = org.osmdroid.events.DelayedMapListener(object : org.osmdroid.events.MapListener {
            private fun saveCenter(): Boolean {
                if (!disposed && vector == null && rasterCenterReady.get()) {
                    raster?.mapCenter?.let { save(it.latitude, it.longitude) }
                }
                return false
            }
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?) = saveCenter()
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?) = saveCenter()
        }, 250L)
        raster?.addMapListener(rasterListener)
        vector?.addOnCameraIdleListener(listener)
        onDispose {
            disposed = true // 解除前に予約されたラスターの遅延通知を無視する。
            runCatching {
                if (vectorCenterReady.get()) vector?.cameraPosition?.target?.let { save(it.latitude, it.longitude) }
                if (vector == null && rasterCenterReady.get()) raster?.mapCenter?.let { save(it.latitude, it.longitude) }
            }
            raster?.removeMapListener(rasterListener)
            vector?.removeOnCameraIdleListener(listener)
            centerSaveThrottle.flush(SystemClock.elapsedRealtime())?.let(::persistCenter)
        }
    }
    // 気圧計はサイコン画面を出している間だけ動かす (電池のため)
    val pressureReader = remember { com.gorite.cyclemap.tracking.PressureReader(state.context) }
    androidx.compose.runtime.DisposableEffect(displayModeState.value == com.gorite.cyclemap.ui.state.DisplayMode.CYCLE_COMPUTER) {
        if (displayModeState.value == com.gorite.cyclemap.ui.state.DisplayMode.CYCLE_COMPUTER) pressureReader.start()
        onDispose { pressureReader.stop() }
    }
    val handednessState = remember {
        androidx.compose.runtime.mutableStateOf(
            com.gorite.cyclemap.ui.state.handednessFromPref(state.prefs.getString(com.gorite.cyclemap.ui.state.PREF_HANDEDNESS, null)),
        )
    }
    val handednessController = remember {
        com.gorite.cyclemap.ui.state.HandednessController(handednessState) { h ->
            handednessState.value = h
            state.prefs.edit().putString(com.gorite.cyclemap.ui.state.PREF_HANDEDNESS, h.name).apply()
        }
    }

    MapTrackingEffects(
        hasLocationPermission = state.hasLocationPermissionState.value,
        appLifecycleState = state.appLifecycleStateState.value,
        isNavigationActive = state.isNavigationActiveState.value,
        isRecording = state.isRecordingState.value,
        trackingController = state.trackingController,
        context = state.context,
        gpsHealthMonitor = state.gpsHealthMonitor,
        gpsStatus = state.gpsStatusState.value,
        onGpsStatusChanged = { state.gpsStatusState.value = it },
        onLocationChanged = { loc ->
            state.currentLocationState.value = loc
            rideSession.onLocation(
                timeMs = loc.time,
                lat = loc.latitude,
                lon = loc.longitude,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy else 100f,
                altitudeM = if (loc.hasAltitude()) loc.altitude else null,
                pressureHpa = pressureReader.latestHpa,
            )
            if (roadRuleEnabledState.value) {
                val graph = state.mappedGraphState.value
                val lock = state.graphLock.readLock()
                // 経路計算や県の切り替えで graph が使われている間は待たない (今回は通知を更新しない)
                if (graph != null && lock.tryLock()) {
                    try {
                        val moving = loc.hasSpeed() && loc.speed >= 1.5f
                        val candidates = if (moving) {
                            graph.roadCandidatesNear(loc.latitude, loc.longitude) +
                                (excludedRoadsState.value?.candidatesNear(loc.latitude, loc.longitude) ?: emptyList())
                        } else emptyList()
                        val match = roadRuleMatcher.update(
                            timeMs = loc.time,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            accuracyM = if (loc.hasAccuracy()) loc.accuracy else 100f,
                            speedMps = if (loc.hasSpeed()) loc.speed else null,
                            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                            candidates = candidates,
                        )
                        roadRuleKindState.value = match?.kind
                    } finally {
                        lock.unlock()
                    }
                }
            }
        },
        lastValidLocation = state.lastValidLocationState.value,
        onLastValidLocationChanged = { state.lastValidLocationState.value = it },
        lastValidBearingDegrees = state.lastValidBearingDegreesState.floatValue,
        onLastValidBearingDegreesChanged = { state.lastValidBearingDegreesState.floatValue = it },
        isHeadingUp = state.isHeadingUpState.value,
        speedKmh = state.speedKmhState.value,
        onSpeedChanged = { state.speedKmhState.value = it },
        navSpeedMps = state.navSpeedMpsState.value,
        onNavSpeedChanged = { state.navSpeedMpsState.value = it },
        navArrowCache = state.navArrowCache,
        orientationAnimator = orientationAnimator,
        onRecordingPointsChanged = { recording, points ->
            state.isRecordingState.value = recording
            if (recording) state.gpxPointCountState.value = points
        },
        navigationRoute = state.navigationRouteState.value,
        // 通過済みを保存対象からも外すので、再探索・復元でも戻らない。
        waypoints = state.waypointsState.value,
        onWaypointsPassed = { count -> state.waypointsState.value = state.waypointsState.value.drop(count) },
        navigationManeuvers = state.navigationManeuversState.value,
        navigationInstructions = state.navigationInstructionsState.value,
        onNavigationProgressChanged = { state.navigationProgressState.value = it },
        lastValidRouteProgressMeters = state.lastValidRouteProgressMetersState.value,
        onLastValidRouteProgressMetersChanged = { state.lastValidRouteProgressMetersState.value = it },
        navStartElapsedMs = state.navStartElapsedMsState.value,
        onNavStatsChanged = { state.navStatsState.value = it },
        isArrived = state.isArrivedState.value,
        onArrivedChanged = {
            if (it && !state.isArrivedState.value) arrivalRide.value = rideSession.snapshot(System.currentTimeMillis())
            state.isArrivedState.value = it
        },
        onNavigationActiveChanged = {
            state.isNavigationActiveState.value = it
            if (!it) state.viewModel.onIntent(com.gorite.cyclemap.ui.map.MapUiIntent.StopNavigation)
        },
        offRouteDetector = state.offRouteDetector,
        voiceNavigator = state.voiceNavigator,
        isRerouting = state.isReroutingState.value,
        autoRerouteEnabled = state.autoRerouteEnabledState.value,
        destination = state.destinationState.value,
        lastRerouteElapsedMs = state.lastRerouteElapsedMsState.value,
        onTriggerReroute = { start, goal ->
            state.lastRerouteElapsedMsState.value = SystemClock.elapsedRealtime()
            runRouteCalculation(start, goal, true, null, null)
        },
        headingDegrees = state.headingDegreesState.floatValue,
        onHeadingChanged = { state.headingDegreesState.floatValue = it },
        onHeadingSensorFixChanged = { state.hasHeadingSensorFixState.value = it },
        onLocationServiceEnabledChanged = { state.isLocationServiceEnabledState.value = it },
    )

    // MVI StateFlow 同期: 高頻度位置情報
    LaunchedEffect(state.currentLocationState.value, state.headingDegreesState.floatValue) {
        val loc = state.currentLocationState.value
        if (loc != null) {
            state.viewModel.updateLocation(
                lat = loc.latitude,
                lon = loc.longitude,
                alt = if (loc.hasAltitude()) loc.altitude else 0.0,
                speed = if (loc.hasSpeed()) loc.speed else 0f,
                bearing = if (loc.hasBearing()) loc.bearing else state.headingDegreesState.floatValue,
                accuracy = if (loc.hasAccuracy()) loc.accuracy else 0f,
            )
        }
    }

    // MVI StateFlow 同期: ナビゲーション進捗
    LaunchedEffect(state.navigationProgressState.value, state.isReroutingState.value) {
        val progress = state.navigationProgressState.value
        val inst = state.navigationInstructionsState.value
        if (progress != null) {
            val remainingDistance = maxOf(0.0, progress.routeDistanceMeters - progress.distanceFromStartMeters)
            val distanceToTurn = progress.distanceToNextManeuverMeters ?: 0.0
            val remainingSec = remainingDistance / 4.16 // 平均15km/h換算
            val upcoming = inst.filter { it.distanceFromStartMeters > progress.distanceFromStartMeters }
            state.viewModel.updateNavProgress(
                distanceToTurn = distanceToTurn,
                remainingDistance = remainingDistance,
                remainingSec = remainingSec,
                currentInst = upcoming.firstOrNull() ?: inst.lastOrNull(),
                nextInst = upcoming.getOrNull(1),
                isRerouting = state.isReroutingState.value,
            )
        }
    }

    // MVI StateFlow 同期: ネットワーク状態
    val currentNetworkLink by state.connectivityState.link.collectAsState()
    LaunchedEffect(currentNetworkLink) {
        state.viewModel.onIntent(com.gorite.cyclemap.ui.map.MapUiIntent.UpdateNetworkLink(currentNetworkLink))
    }

    val weatherRepo = remember(state.context) { com.gorite.cyclemap.weather.WeatherRepository.getInstance(state.context) }
    val weatherUiState by weatherRepo.uiState.collectAsState()
    val isOnline = currentNetworkLink !is com.gorite.cyclemap.net.Link.Offline
    val currentCityHint = com.gorite.cyclemap.weather.weatherCityHint(state.locationLabelState.value)
    LaunchedEffect(state.currentLocationState.value, currentCityHint, isOnline) {
        val loc = state.currentLocationState.value
        val lat = loc?.latitude ?: FALLBACK_LATITUDE
        val lon = loc?.longitude ?: FALLBACK_LONGITUDE
        weatherRepo.updateLocation(lat, lon, cityHint = currentCityHint, isOnline = isOnline)
    }

    // 経路プレビューで、別の候補を選んで主線 (案内する経路) にする。
    // 案内の指示・曲がり角・概要を、選んだ候補の経路で作り直す。標高プロファイルは経路の変化に追従して再計算される。
    val routeSelectScope = androidx.compose.runtime.rememberCoroutineScope()
    val selectRouteOption: (Int) -> Unit = select@{ index ->
        val options = state.calculatedRouteOptionsState.value
        val option = options.getOrNull(index) ?: return@select
        if (index == state.selectedRouteIndexState.value || option.points.size < 2) return@select
        if (state.isNavigationActiveState.value) return@select
        val graph = state.mappedGraphState.value
        routeSelectScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val nodeEnd = option.nodePointOffset + option.nodeIds.size
            val nodePoints = if (option.nodePointOffset >= 0 && nodeEnd <= option.points.size) {
                option.points.subList(option.nodePointOffset, nodeEnd)
            } else emptyList()
            val headDistance = com.gorite.cyclemap.navigation.displayedRouteDistance(
                option.points.take(option.nodePointOffset.coerceAtLeast(0) + 1),
            )
            val instructions = if (graph != null && option.nodeIds.size == nodePoints.size) {
                try {
                    state.graphLock.read {
                        com.gorite.cyclemap.navigation.buildRouteInstructions(graph, option.nodeIds, nodePoints)
                            .map { it.copy(distanceFromStartMeters = it.distanceFromStartMeters + headDistance) }
                    }
                } catch (t: Throwable) {
                    Log.w("CycleMapInstruction", "alternative route instruction build failed", t)
                    emptyList()
                }
            } else {
                emptyList()
            }
            val navRoute = option.points.map { (lat, lon) -> RoutePoint(lat, lon) }
            val maneuvers = com.gorite.cyclemap.routing.extractManeuvers(navRoute)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                // 選んでいる間に経路が消された・計算し直された・案内が始まった場合は、何もしない
                if (state.calculatedRouteOptionsState.value !== options || state.isNavigationActiveState.value) {
                    return@withContext
                }
                navigationEndPrompt.onRouteReplaced()
                state.navigationRouteState.value = navRoute
                state.navigationManeuversState.value = maneuvers
                state.navigationInstructionsState.value = instructions
                state.routeSummaryState.value = RouteSummary(option.distanceMeters, (navRoute.size - 1).coerceAtLeast(0))
                state.selectedRouteIndexState.value = index
                Log.i("CycleMapRoute", "ROUTE_OPTION_SELECTED index=$index id=${option.id} distanceM=${option.distanceMeters.toInt()}")
            }
        }
    }

    val startNavigation: () -> Unit = {
        arrivalRide.value = null
        val loc = state.currentLocationState.value
        val navRoute = state.navigationRouteState.value
        val navInst = state.navigationInstructionsState.value
        val dest = state.destinationState.value
        if (BuildConfig.DEBUG) {
            Log.i(
                "CycleMapRoute",
                "GUIDANCE_START currentLocation=${if (loc != null) "${loc.latitude},${loc.longitude} acc=${loc.accuracy}" else "(null)"} " +
                    "destination=${dest?.let { "${it.latitude},${it.longitude}" } ?: "(null)"} " +
                    "routeNodeCount=${navRoute.size} instructionCount=${navInst.size} " +
                    "wasActive=${state.isNavigationActiveState.value}",
            )
        }
        if (navRoute.isNotEmpty()) {
            state.followLocationState.value = true
            state.navStartElapsedMsState.value = SystemClock.elapsedRealtime()
            state.rerouteCountState.intValue = 0
            state.rerouteLimitWarnedState.value = false
            state.lastRerouteElapsedMsState.value = 0L
            state.isArrivedState.value = false
            state.navStatsState.value = null
            state.offRouteDetector.reset()
            state.voiceNavigator.reset()
            navigationEndPrompt.onNavigationStarted()
            state.isNavigationActiveState.value = true
            state.mapViewState.value?.controller?.setZoom(NAVIGATION_ZOOM)
            loc?.let { location ->
                state.mapViewState.value?.let { view ->
                    view.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                }
                // 追従の moveCamera に打ち切られないよう、終わるまで追従を止めてズームする
                state.vectorMapState.value?.animateCameraExclusive(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                        org.maplibre.android.geometry.LatLng(location.latitude, location.longitude),
                        NAVIGATION_ZOOM,
                    ),
                )
                state.navigationProgressState.value = calculateRouteProgress(
                    RoutePoint(location.latitude, location.longitude),
                    navRoute,
                    state.navigationManeuversState.value,
                )
            }
            Log.i("CycleMapRoute", "GUIDANCE_STATE isNavigationActive=true routeDisplayed=true")
            // 案内中に見せるのは案内する 1 本だけ。代替ルート (灰色) は消す
            state.calculatedRouteOptionsState.value = emptyList()
            state.selectedRouteIndexState.value = 0
            state.viewModel.onIntent(com.gorite.cyclemap.ui.map.MapUiIntent.StartNavigation)
        } else {
            Log.e("CycleMapRoute", "ROUTE_FAILURE reason=start-with-empty-route")
            state.warningMessageState.value = "ルートがまだ確定していません。探索完了をお待ちください"
        }
    }

    // アプリが裏で終了させられたあとに開き直したとき、前回のルートを計算し直す (案内中だったら案内も再開する)。
    // ルートの点の列は保存していないので、保存した目的地・経由地・好みから探索し直す。
    LaunchedEffect(Unit) {
        val restore = state.viewModel.consumePendingRestore() ?: return@LaunchedEffect
        Log.i(
            "CycleMapRoute",
            "SESSION_RESTORE dest=${restore.destinationLat},${restore.destinationLon} " +
                "waypoints=${restore.waypoints.size} pref=${restore.preferenceName} navigating=${restore.wasNavigating}",
        )
        state.selectedRoutePreferenceState.value = restore.preference
        state.destinationState.value = restore.destination
        restoredDestination.value = restore.destination
        state.warningMessageState.value = WAITING_FOR_LOCATION
        val start = androidx.compose.runtime.snapshotFlow {
            manualRestoreOrigin.value ?: state.startMarkerState.value?.position ?: state.currentLocationState.value?.let { GeoPoint(it.latitude, it.longitude) }
        }.filterNotNull().first()
        restoredDestination.value = null
        if (state.destinationState.value != restore.destination || state.navigationRouteState.value.isNotEmpty()) return@LaunchedEffect
        state.warningMessageState.value = null
        runRouteCalculation(start, restore.destination, false, restore.waypoints, restore.preference)
        if (restore.wasNavigating) {
            val routed = kotlinx.coroutines.withTimeoutOrNull(SESSION_RESTORE_ROUTE_WAIT_MS) {
                androidx.compose.runtime.snapshotFlow { state.navigationRouteState.value.isNotEmpty() && !state.isCalculatingRouteState.value }
                    .first { it }
            }
            if (routed != null && !state.isNavigationActiveState.value) {
                startNavigation()
                state.warningMessageState.value = "前回の案内を再開しました"
            }
        }
    }

    val clearRoute: () -> Unit = {
        val shouldAskContinueRecording = navigationEndPrompt.shouldPromptOnClear(state.isRecordingState.value)
        waitingDestination.value = null
        restoredDestination.value = null
        routeExecutionGeneration.incrementAndGet()
        state.inFlightRouteRef.set(null)
        state.viewModel.onIntent(com.gorite.cyclemap.ui.map.MapUiIntent.StopNavigation)
        executeClearRoute(
            routeJobRef = state.routeJobRef,
            pendingRouteRef = state.pendingRouteRef,
            mapView = state.mapViewState.value,
            routeOverlay = state.routeOverlayState.value,
            gpxTrackOverlay = state.gpxTrackOverlayState.value,
            destinationMarker = state.destinationMarkerState.value,
            startMarker = state.startMarkerState.value,
            waypointMarkers = state.waypointMarkersState.value,
            offRouteDetector = state.offRouteDetector,
            voiceNavigator = state.voiceNavigator,
            onResetStates = {
                state.routeOverlayState.value = null
                state.gpxTrackOverlayState.value = null
                state.destinationMarkerState.value = null
                state.startMarkerState.value = null
                state.waypointMarkersState.value = emptyList()
                state.waypointsState.value = emptyList()
                state.routeSummaryState.value = null
                state.destinationState.value = null
                state.calculatedRouteOptionsState.value = emptyList() // 灰色の代替ルートも消す
                state.selectedRouteIndexState.value = 0
                state.navigationRouteState.value = emptyList()
                state.navigationManeuversState.value = emptyList()
                state.navigationInstructionsState.value = emptyList()
                state.navigationProgressState.value = null
                state.navStatsState.value = null
                state.isNavigationActiveState.value = false
                state.isCalculatingRouteState.value = false
                state.isReroutingState.value = false
                state.isArrivedState.value = false
                state.rerouteCountState.intValue = 0
                state.rerouteLimitWarnedState.value = false
                state.lastRerouteElapsedMsState.value = 0L
                state.isDownloadingCorridorTilesState.value = false
                state.corridorTileProgressTextState.value = null
            },
        )
        if (shouldAskContinueRecording) {
            showContinueRecordingConfirm.value = true
        }
    }

    val exportPlannedRoute: () -> Unit = {
        val name = "CycleMap_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}"
        val xml = com.gorite.cyclemap.tracking.buildRouteGpx(state.navigationRouteState.value, name, System.currentTimeMillis())
        if (xml == null) {
            android.widget.Toast.makeText(state.context, "書き出すルートがありません", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            pendingRouteGpx.value = xml
            exportRouteLauncher.launch("$name.gpx")
        }
    }

    val launchRouteImport: () -> Unit = {
        state.showCyclingSettingsState.value = false
        importRouteLauncher.launch(arrayOf("application/gpx+xml", "application/octet-stream", "text/xml", "*/*"))
    }

    val applyLayer: (MapLayer) -> Unit = { nextLayer ->
        state.selectedLayerState.value = nextLayer
        val mode = when (nextLayer) {
            MapLayer.VECTOR_LIBERTY -> com.gorite.cyclemap.ui.map.MapLayerMode.Vector3D
            MapLayer.GSI -> com.gorite.cyclemap.ui.map.MapLayerMode.GsiStandard
            MapLayer.OSM -> com.gorite.cyclemap.ui.map.MapLayerMode.OpenCycleMap
            MapLayer.TERRAIN -> com.gorite.cyclemap.ui.map.MapLayerMode.Hillshade
            else -> com.gorite.cyclemap.ui.map.MapLayerMode.Vector3D
        }
        state.viewModel.onIntent(com.gorite.cyclemap.ui.map.MapUiIntent.SwitchLayer(mode))
    }
    val toggleNextLayer: () -> Unit = {
        applyLayer(determineNextLayer(state.context, state.selectedLayerState.value))
    }
    // 一覧から選んだレイヤー。地形はオフラインでは使えないので、使えない理由を伝えて切り替えない
    val selectLayer: (MapLayer) -> Unit = { layer ->
        if (layer == MapLayer.TERRAIN && !isNetworkAvailable(state.context)) {
            android.widget.Toast.makeText(state.context, "地形起伏はオフラインでは使えません", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            applyLayer(layer)
        }
    }

    val downloadCorridorTiles: () -> Unit = {
        if (!state.isDownloadingCorridorTilesState.value && state.navigationRouteState.value.isNotEmpty()) {
            state.isDownloadingCorridorTilesState.value = true
            executeDownloadCorridorTiles(
                context = state.context,
                navigationRoute = state.navigationRouteState.value,
                selectedLayer = state.selectedLayerState.value,
                onProgressUpdate = { state.corridorTileProgressTextState.value = it },
                onFinished = { state.isDownloadingCorridorTilesState.value = false },
            )
        }
    }

    val runRoutingBenchmark: (RoutingBenchmarkRequest) -> Unit = { request ->
        if (!state.isRoutingBenchmarkRunningState.value) {
            state.isRoutingBenchmarkRunningState.value = true
            state.routingBenchmarkResultState.value = null
            executeRoutingBenchmark(
                context = state.context,
                scope = state.scope,
                request = request,
                graphLock = state.graphLock,
                mappedGraph = state.mappedGraphState.value,
                onResult = { state.routingBenchmarkResultState.value = it },
                onFinished = { state.isRoutingBenchmarkRunningState.value = false },
            )
        }
    }

    val routeHazards = com.gorite.cyclemap.ui.hazard.rememberRouteHazards(state.navigationRouteState.value)
    androidx.compose.runtime.CompositionLocalProvider(
        com.gorite.cyclemap.ui.state.LocalDisplayModeController provides displayModeController,
        com.gorite.cyclemap.ui.state.LocalHandednessController provides handednessController,
        com.gorite.cyclemap.ui.state.LocalRoadRuleController provides roadRuleController,
        com.gorite.cyclemap.ui.hazard.LocalRouteHazards provides routeHazards,
    ) {
    MapScreenScaffold(
        state = state,
        routePreviewSheetHeightPx = routePreviewSheetHeightPx.intValue,
        onRoutePreviewSheetHeightChanged = { routePreviewSheetHeightPx.intValue = it },
        onWaitingForStartPoint = { dest ->
            // 再計算は表示中の目的地を渡すため、すでに待機中の目的地があれば優先する。
            waitForStartPoint(waitingDestination.value ?: dest)
        },
        rideSnapshot = arrivalRide.value,
        modifier = modifier,
        systemInsets = systemInsets,
        isDarkTheme = isDarkTheme,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        permissionLauncher = permissionLauncher,
        orientationAnimator = orientationAnimator,
        runRouteCalculation = runRouteCalculation,
        startRouteToDestination = startRouteToDestination,
        selectRouteOption = selectRouteOption,
        handleSpotSelected = handleSpotSelected,
        runRoutingBenchmark = runRoutingBenchmark,
        downloadCorridorTiles = downloadCorridorTiles,
        exportPlannedRoute = exportPlannedRoute,
        importPlannedRoute = launchRouteImport,
        toggleNextLayer = toggleNextLayer,
        selectLayer = selectLayer,
        startNavigation = startNavigation,
        clearRoute = clearRoute,
        weatherUiState = weatherUiState,
        onRefreshWeather = { weatherRepo.refresh(isOnline) },
    )
    // サイコンモード: 地図の上に全面で重ねる (地図は下で動き続ける。位置・ルート・案内の状態は共有)。
    if (displayModeState.value == com.gorite.cyclemap.ui.state.DisplayMode.CYCLE_COMPUTER) {
        com.gorite.cyclemap.ui.cycling.CycleComputerHost(
            rideSession = rideSession,
            store = rideSessionStore,
            onStartRecording = { com.gorite.cyclemap.tracking.LocationTrackingService.startRecording(state.context) },
            onPauseRecording = { com.gorite.cyclemap.tracking.LocationTrackingService.pauseRecording(state.context) },
            onFinishRecording = { com.gorite.cyclemap.tracking.LocationTrackingService.stopRecording(state.context) },
            isGpxRecording = state.isRecordingState.value,
            location = state.currentLocationState.value,
            destLat = state.destinationState.value?.latitude,
            destLon = state.destinationState.value?.longitude,
            progress = state.navigationProgressState.value,
            onExit = { displayModeController.set(com.gorite.cyclemap.ui.state.DisplayMode.NORMAL) },
        )
    }
    if (showContinueRecordingConfirm.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showContinueRecordingConfirm.value = false },
            title = { androidx.compose.material3.Text("記録は続けますか?") },
            text = { androidx.compose.material3.Text("ナビは終了しましたが、GPXの走行記録は続いています。") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showContinueRecordingConfirm.value = false },
                ) {
                    androidx.compose.material3.Text("記録を続ける")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showContinueRecordingConfirm.value = false
                        com.gorite.cyclemap.tracking.LocationTrackingService.stopRecording(state.context)
                    },
                ) {
                    androidx.compose.material3.Text("記録を終えて保存")
                }
            },
        )
    }
    }
}

/** ルートプレビューで引く下限のズーム。これ未満はタイルが粗い陸地ポリゴンだけになる。 */
private const val PREVIEW_MIN_ZOOM = 6.0
private const val WAITING_FOR_LOCATION = com.gorite.cyclemap.navigation.WAITING_FOR_START_POINT_MESSAGE

/** 案内を再開する前に、ルートの計算が終わるのを待つ時間 (県またぎでも足りる長さ)。 */
private const val SESSION_RESTORE_ROUTE_WAIT_MS = 60_000L
