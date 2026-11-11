package com.gorite.cyclemap.ui.screens

import com.gorite.cyclemap.*
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

    val orientationAnimator = remember {
        MapOrientationAnimator(
            getMapView = { state.mapViewState.value },
            getVectorMap = { state.vectorMapState.value },
            isVectorActive = { state.selectedLayerState.value == com.gorite.cyclemap.utils.MapLayer.VECTOR_LIBERTY },
            onOrientationUpdated = { state.updateNavArrow() },
        )
    }

    val latestGraph by rememberUpdatedState(state.mappedGraphState.value)
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
        routeJobRef = state.routeJobRef,
        routeExecutionGeneration = routeExecutionGeneration,
        inFlightRouteRef = state.inFlightRouteRef,
        graphLock = state.graphLock,
        pendingRouteRef = state.pendingRouteRef,
        getGraph = { latestGraph },
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

    val startRouteToDestination: (GeoPoint) -> Unit = { point ->
        val loc = latestLocation
        val start = if (loc != null) {
            GeoPoint(loc.latitude, loc.longitude)
        } else {
            GeoPoint(FALLBACK_LATITUDE, FALLBACK_LONGITUDE)
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                "CycleMapRoute",
                "DESTINATION_SET dest=${point.latitude},${point.longitude} " +
                    "currentLocation=${if (loc != null) "${loc.latitude},${loc.longitude} acc=${loc.accuracy}" else "null->FALLBACK $FALLBACK_LATITUDE,$FALLBACK_LONGITUDE"}",
            )
        }
        runRouteCalculation(start, point, false, null, null)
    }

    val handleSpotSelected: (GeoPoint) -> Unit = { point ->
        if (state.destinationState.value != null) {
            state.pendingSelectedSpotState.value = point
        } else {
            startRouteToDestination(point)
        }
    }

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
            state.isRecordingState.value = false
            state.gpxPointCountState.value = 0
            state.gpxNotificationTextState.value = "位置情報を取得できなかったため記録しませんでした"
        },
        onGpxRecordingSaved = { points, fileName ->
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
    LaunchedEffect(curMapView, curNavRoute, curDest, curWaypoints) {
        val view = curMapView ?: return@LaunchedEffect

        // 目的地の再構築
        if (curDest != null && state.destinationMarkerState.value == null) {
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
        if (curNavRoute.isNotEmpty() && state.routeOverlayState.value == null) {
            val polyline = org.osmdroid.views.overlay.Polyline(view).apply {
                setPoints(curNavRoute.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = android.graphics.Color.parseColor("#333333")
                outlinePaint.strokeWidth = 24f
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.isAntiAlias = true
            }
            view.overlays.add(polyline)
            state.routeOverlayState.value = polyline
        }
        view.invalidate()
    }

    LaunchedEffect(state.navigationRouteState.value, state.mapViewState.value, state.vectorMapState.value, state.isNavigationActiveState.value) {
        val navRoute = state.navigationRouteState.value
        if (navRoute.isEmpty() || state.isNavigationActiveState.value) return@LaunchedEffect
        // Preview中はGPS追従を止めて、全ルートのカメラフィットを維持する。
        state.followLocationState.value = false
        withFrameNanos { }
        val points = navRoute.map { GeoPoint(it.latitude, it.longitude) }
        val bounds = BoundingBox.fromGeoPoints(points)
        state.mapViewState.value?.zoomToBoundingBox(bounds, true, 64)
        state.vectorMapState.value?.let { vMap ->
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            val expanded = expandBoundsToMinSpan(minLat, maxLat, minLon, maxLon)
            val padding = previewPaddingPx(screenHeightPx, density.density, state.waypointsState.value.size)
            val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
            boundsBuilder.include(org.maplibre.android.geometry.LatLng(expanded.minLat, expanded.minLon))
            boundsBuilder.include(org.maplibre.android.geometry.LatLng(expanded.maxLat, expanded.maxLon))
            val latLngBounds = boundsBuilder.build()
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        state.hasLocationPermissionState.value = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
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
        onLocationChanged = { state.currentLocationState.value = it },
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
        navigationManeuvers = state.navigationManeuversState.value,
        navigationInstructions = state.navigationInstructionsState.value,
        onNavigationProgressChanged = { state.navigationProgressState.value = it },
        lastValidRouteProgressMeters = state.lastValidRouteProgressMetersState.value,
        onLastValidRouteProgressMetersChanged = { state.lastValidRouteProgressMetersState.value = it },
        navStartElapsedMs = state.navStartElapsedMsState.value,
        onNavStatsChanged = { state.navStatsState.value = it },
        isArrived = state.isArrivedState.value,
        onArrivedChanged = { state.isArrivedState.value = it },
        onNavigationActiveChanged = { state.isNavigationActiveState.value = it },
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
    val currentCityHint = state.locationLabelState.value
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
            val instructions = if (graph != null && option.nodeIds.size == option.points.size) {
                try {
                    state.graphLock.read {
                        com.gorite.cyclemap.navigation.buildRouteInstructions(graph, option.nodeIds, option.points)
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
            state.isNavigationActiveState.value = true
            state.mapViewState.value?.controller?.setZoom(NAVIGATION_ZOOM)
            loc?.let { location ->
                state.mapViewState.value?.let { view ->
                    view.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                }
                state.vectorMapState.value?.animateCamera(
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

    val clearRoute: () -> Unit = {
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

    MapScreenScaffold(
        state = state,
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
}
