package com.gorite.cyclemap.ui.effects

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.core.location.LocationManagerCompat
import com.gorite.cyclemap.routing.AdaptiveRerouteManager
import com.gorite.cyclemap.routing.DeadReckoningResult
import com.gorite.cyclemap.routing.DeadReckoner
import com.gorite.cyclemap.routing.Maneuver
import com.gorite.cyclemap.routing.NavigationStats
import com.gorite.cyclemap.routing.OffRouteDetector
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.routing.computeNavigationStats
import com.gorite.cyclemap.speech.VoiceGuidanceNavigator
import com.gorite.cyclemap.tracking.AppLifecycleState
import com.gorite.cyclemap.tracking.GpsHealthMonitor
import com.gorite.cyclemap.tracking.GpsSignalStatus
import com.gorite.cyclemap.tracking.TrackingStateController
import com.gorite.cyclemap.routing.ArrivalDetector
import com.gorite.cyclemap.routing.RouteProgressIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.osmdroid.util.GeoPoint

@Composable
internal fun MapTrackingEffects(
    hasLocationPermission: Boolean,
    appLifecycleState: AppLifecycleState,
    isNavigationActive: Boolean,
    isRecording: Boolean,
    trackingController: TrackingStateController,
    context: Context,
    gpsHealthMonitor: GpsHealthMonitor,
    gpsStatus: GpsSignalStatus,
    onGpsStatusChanged: (GpsSignalStatus) -> Unit,
    onLocationChanged: (Location) -> Unit,
    lastValidLocation: Location?,
    onLastValidLocationChanged: (Location) -> Unit,
    lastValidBearingDegrees: Float,
    onLastValidBearingDegreesChanged: (Float) -> Unit,
    isHeadingUp: Boolean,
    speedKmh: Double,
    onSpeedChanged: (Double) -> Unit,
    navSpeedMps: Double,
    onNavSpeedChanged: (Double) -> Unit,
    navArrowCache: NavArrowCache,
    orientationAnimator: MapOrientationAnimator,
    onRecordingPointsChanged: (Boolean, Int) -> Unit,
    navigationRoute: List<RoutePoint>,
    navigationManeuvers: List<Maneuver>,
    navigationInstructions: List<RouteInstruction>,
    onNavigationProgressChanged: (RouteProgress?) -> Unit,
    lastValidRouteProgressMeters: Double?,
    onLastValidRouteProgressMetersChanged: (Double?) -> Unit,
    navStartElapsedMs: Long,
    onNavStatsChanged: (NavigationStats?) -> Unit,
    isArrived: Boolean,
    onArrivedChanged: (Boolean) -> Unit,
    onNavigationActiveChanged: (Boolean) -> Unit,
    offRouteDetector: OffRouteDetector,
    voiceNavigator: VoiceGuidanceNavigator,
    isRerouting: Boolean,
    autoRerouteEnabled: Boolean,
    destination: GeoPoint?,
    lastRerouteElapsedMs: Long,
    onTriggerReroute: (start: GeoPoint, goal: GeoPoint) -> Unit,
    headingDegrees: Float,
    onHeadingChanged: (Float) -> Unit,
    onHeadingSensorFixChanged: (Boolean) -> Unit,
    onLocationServiceEnabledChanged: (Boolean) -> Unit = {},
    waypoints: List<GeoPoint> = emptyList(),
    onWaypointsPassed: (Int) -> Unit = {},
) {
    // 端末の位置情報サービス (GPS Provider) オン/オフの監視
    DisposableEffect(context) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val enabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
                onLocationServiceEnabledChanged(enabled)
            }
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        context.registerReceiver(receiver, filter)
        val initialEnabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
        onLocationServiceEnabledChanged(initialEnabled)

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
                // 解除済みなら何もしなくてよい
            }
        }
    }

    LaunchedEffect(appLifecycleState) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val enabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
        onLocationServiceEnabledChanged(enabled)
    }

    val adaptiveRerouteManager = remember {
        AdaptiveRerouteManager(
            rerouteDistanceThreshold = 30.0,
            consecutiveSamples = 3,
            routerAction = { _, _ -> null }
        )
    }

    LaunchedEffect(navigationRoute) {
        if (navigationRoute.isNotEmpty()) {
            adaptiveRerouteManager.setInitialRoute(navigationRoute.indices.map { it.toLong() })
        } else {
            adaptiveRerouteManager.reset()
        }
    }

    // 最後に再計算を要求した地点。道路から離れた場所 (建物内など) では、再計算しても同じ経路が返り
    // 「経路から外れた」判定が続くため、ほとんど動いていない間は再計算を繰り返さない。
    val lastRerouteFrom = remember { arrayOfNulls<RoutePoint>(1) }

    LaunchedEffect(isNavigationActive) {
        if (!isNavigationActive) {
            adaptiveRerouteManager.reset()
        }
        lastRerouteFrom[0] = null
    }

    // 位置追跡サービス (Foreground Service) の起動・停止ポリシー制御
    LaunchedEffect(hasLocationPermission, appLifecycleState, isNavigationActive, isRecording) {
        if (!hasLocationPermission) {
            if (trackingController.isTrackingActive) {
                trackingController.update(AppLifecycleState.PAUSED_OR_STOPPED, isNavigating = false, isRecording = false)
            }
            return@LaunchedEffect
        }
        trackingController.update(
            lifecycleState = appLifecycleState,
            isNavigating = isNavigationActive,
            isRecording = isRecording,
        )
    }

    val graphNavigationState = remember(context) { navigationGraphState(context) }
    DisposableEffect(graphNavigationState, isNavigationActive) {
        graphNavigationState?.active = isNavigationActive
        onDispose { graphNavigationState?.active = false }
    }
    val arrivalDetector = remember(navigationRoute, isNavigationActive) { ArrivalDetector() }
    val navigationFixes = remember(navigationRoute, isNavigationActive) { Channel<Location>(Channel.CONFLATED) }
    var progressIndex by remember(navigationRoute) { mutableStateOf<RouteProgressIndex?>(null) }
    LaunchedEffect(navigationRoute) {
        progressIndex = withContext(Dispatchers.Default) { RouteProgressIndex(navigationRoute) }
    }
    val currentOnWaypointsPassed by rememberUpdatedState(onWaypointsPassed)
    val currentRoute by rememberUpdatedState(navigationRoute)
    val currentNavigationActive by rememberUpdatedState(isNavigationActive)
    val currentValidLocation by rememberUpdatedState(lastValidLocation)
    val processNavigationFix by rememberUpdatedState<(Location, RouteProgress?) -> Unit>({ location, progress ->
        if (isNavigationActive && navigationRoute.isNotEmpty()) {
            onNavigationProgressChanged(progress)
            if (progress != null) {
                onLastValidRouteProgressMetersChanged(progress.distanceFromStartMeters)
            }
            val stats = computeNavigationStats(
                progress = progress,
                smoothedSpeedMps = navSpeedMps,
                navStartElapsedRealtimeMs = navStartElapsedMs,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                nowEpochMillis = System.currentTimeMillis(),
                arrivalRadiusMeters = ARRIVAL_RADIUS_METERS,
                arrivalConfirmed = arrivalDetector.update(
                    progress,
                    // 道路から離れた目的地で端点の線が省略されても、目的地そのものまで測る。
                    destination?.let { haversineMeters(location.latitude, location.longitude, it.latitude, it.longitude) }
                        ?: navigationRoute.lastOrNull()?.let { haversineMeters(location.latitude, location.longitude, it.latitude, it.longitude) }
                        ?: Double.POSITIVE_INFINITY,
                    if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                    location.elapsedRealtimeNanos,
                ),
            )
            onNavStatsChanged(stats)
            if (stats?.isArrived == true) {
                if (!isArrived) {
                    onArrivedChanged(true)
                    onNavigationActiveChanged(false)
                    offRouteDetector.reset()
                    voiceNavigator.onLocationUpdated(
                        traveledMeters = progress?.distanceFromStartMeters ?: 0.0,
                        remainingMeters = 0.0,
                        instructions = navigationInstructions,
                        isOffRoute = false,
                    )
                }
            } else if (progress != null && !isRerouting) {
                adaptiveRerouteManager.onLocationUpdate(
                    distanceToRouteMeters = progress.distanceToRouteMeters,
                    currentSpeedMps = location.speed,
                    currentNodeId = progress.distanceFromStartMeters.toLong(),
                    goalNodeId = navigationRoute.lastIndex.toLong(),
                )
                val offRoute = adaptiveRerouteManager.state.value.isOffRoute
                val remaining = (progress.routeDistanceMeters - progress.distanceFromStartMeters).coerceAtLeast(0.0)
                voiceNavigator.onLocationUpdated(
                    traveledMeters = progress.distanceFromStartMeters,
                    remainingMeters = remaining,
                    instructions = navigationInstructions,
                    isOffRoute = offRoute,
                )
                if (offRoute && autoRerouteEnabled) {
                    val now = SystemClock.elapsedRealtime()
                    val dest = destination
                    val here = RoutePoint(location.latitude, location.longitude)
                    val from = lastRerouteFrom[0]
                    val movedSinceReroute = from == null || haversineMeters(from.latitude, from.longitude, here.latitude, here.longitude) >= REROUTE_MIN_MOVE_METERS
                    if (dest != null && movedSinceReroute && now - lastRerouteElapsedMs >= REROUTE_MIN_INTERVAL_MS) {
                        lastRerouteFrom[0] = here
                        onTriggerReroute(
                            GeoPoint(location.latitude, location.longitude),
                            dest,
                        )
                    }
                }
            }
        }
    })
    LaunchedEffect(navigationRoute, isNavigationActive, progressIndex) {
        val index = progressIndex ?: return@LaunchedEffect
        if (!isNavigationActive) return@LaunchedEffect
        val waypointTracker = withContext(Dispatchers.Default) {
            WaypointPassTracker(index, waypoints.map { RoutePoint(it.latitude, it.longitude) })
        }
        var lastPassed = 0
        var previous: Double? = null
        var previousFixNanos: Long? = null
        for (location in navigationFixes) {
            if (!isNavigationActive) continue
            val elapsed = previousFixNanos?.let { (location.elapsedRealtimeNanos - it).coerceAtLeast(0L) / 1e9 } ?: 0.0
            val progress = withContext(Dispatchers.Default) {
                index.calculate(RoutePoint(location.latitude, location.longitude), navigationManeuvers,
                    previous, location.speed.toDouble().coerceAtLeast(0.0) * elapsed,
                    location.bearing.toDouble().takeIf { location.hasBearing() && location.hasSpeed() && location.speed >= PROGRESS_BEARING_MIN_SPEED_MPS })
            }
            // 計算中に経路が変わった測位の結果は、新しい経路へ反映しない。
            if (currentRoute !== navigationRoute || !currentNavigationActive) continue
            previous = progress?.distanceFromStartMeters
            previousFixNanos = location.elapsedRealtimeNanos
            val passed = waypointTracker.update(RoutePoint(location.latitude, location.longitude),
                if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY, progress)
            if (passed > lastPassed) {
                currentOnWaypointsPassed(passed - lastPassed)
                lastPassed = passed
            }
            processNavigationFix(location, progress)
        }
    }

    if (hasLocationPermission) {
        ServiceLocationUpdates(
            context = context,
            onLocationChanged = { location, recording, points ->
                gpsHealthMonitor.onValidFix()
                onGpsStatusChanged(gpsHealthMonitor.status)
                onLocationChanged(location)
                onLastValidLocationChanged(location)
                if (location.hasBearing() && location.bearing != 0f) {
                    onLastValidBearingDegreesChanged(location.bearing)
                }
                if (isHeadingUp && (speedKmh >= SPEED_HEADING_THRESHOLD_KMH || (location.hasSpeed() && location.speed >= SPEED_HEADING_THRESHOLD_MPS))) {
                    val moveBearing = if (location.hasBearing() && location.bearing != 0f) {
                        location.bearing
                    } else if (navArrowCache.courseDeg.isFinite()) {
                        navArrowCache.courseDeg
                    } else if (lastValidBearingDegrees != 0f) {
                        lastValidBearingDegrees
                    } else null
                    if (moveBearing != null) {
                        orientationAnimator.rotateTo(moveBearing)
                    }
                }
                onRecordingPointsChanged(recording, points)
                navigationFixes.trySend(Location(location))
            },
            onSpeedChanged = { speed ->
                onSpeedChanged(speed * 3.6)
                onNavSpeedChanged(speed)
            },
            onGpsStatusChanged = { status ->
                if (status == GpsSignalStatus.WEAK) {
                    gpsHealthMonitor.onAccuracyDegraded()
                    onGpsStatusChanged(gpsHealthMonitor.status)
                } else if (status == GpsSignalStatus.HEALTHY) {
                    gpsHealthMonitor.onValidFix()
                    onGpsStatusChanged(gpsHealthMonitor.status)
                }
            },
        )
    }

    // 長く動くループでも位置・速度・方位・進捗と通知先を毎回最新にする。
    val timeoutTick by rememberUpdatedState<suspend () -> Unit>(tick@{
        val updated = gpsHealthMonitor.tick()
        if (gpsStatus != updated) {
            onGpsStatusChanged(updated)
        }

        // WEAK 状態かつ直近の有効位置がある場合、最大15秒間推測移動を実行
        val validLoc = lastValidLocation
        if (updated == GpsSignalStatus.WEAK && validLoc != null && gpsHealthMonitor.hasReceivedFirstFix) {
            val elapsedSec = (SystemClock.elapsedRealtime() - gpsHealthMonitor.lastFixMs) / 1000.0
            val speed = navSpeedMps.takeIf { it.isFinite() && it >= 0.0 } ?: validLoc.speed.toDouble()
            val lastBearing = if (lastValidBearingDegrees != 0f) lastValidBearingDegrees.toDouble() else headingDegrees.toDouble()
            val index = progressIndex
            val progressMeters = lastValidRouteProgressMeters
            val drResult = withContext(Dispatchers.Default) {
                val straight = DeadReckoner.estimate(
                    lastLocation = RoutePoint(validLoc.latitude, validLoc.longitude),
                    lastBearingDegrees = lastBearing,
                    speedMps = speed,
                    elapsedSeconds = elapsedSec,
                )
                val along = if (isNavigationActive && progressMeters != null && straight.distanceAdvancedMeters > 0.0) {
                    index?.pointAt(progressMeters + straight.distanceAdvancedMeters)
                } else null
                if (along != null) DeadReckoningResult(along.first, along.second ?: lastBearing,
                    straight.distanceAdvancedMeters, straight.isExpired) else straight
            }

            if (currentValidLocation?.elapsedRealtimeNanos != validLoc.elapsedRealtimeNanos) return@tick
            if (drResult.distanceAdvancedMeters > 0.0) {
                val estLocation = Location(validLoc).apply {
                    latitude = drResult.location.latitude
                    longitude = drResult.location.longitude
                    bearing = drResult.bearingDegrees.toFloat()
                    accuracy = 100f
                    this.speed = speed.toFloat()
                    time = System.currentTimeMillis()
                }
                onLocationChanged(estLocation)

                if (isNavigationActive && navigationRoute.isNotEmpty()) {
                    val index = progressIndex
                    val progress = withContext(Dispatchers.Default) {
                        index?.calculate(drResult.location, navigationManeuvers, lastValidRouteProgressMeters,
                            drResult.distanceAdvancedMeters, drResult.bearingDegrees)
                    }
                    if (progress != null) {
                        onNavigationProgressChanged(progress)
                        val stats = computeNavigationStats(
                            progress = progress,
                            smoothedSpeedMps = navSpeedMps,
                            navStartElapsedRealtimeMs = navStartElapsedMs,
                            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            nowEpochMillis = System.currentTimeMillis(),
                            arrivalRadiusMeters = ARRIVAL_RADIUS_METERS,
                        )
                        onNavStatsChanged(stats)
                        val remaining = (progress.routeDistanceMeters - progress.distanceFromStartMeters).coerceAtLeast(0.0)
                        voiceNavigator.onLocationUpdated(
                            traveledMeters = progress.distanceFromStartMeters,
                            remainingMeters = remaining,
                            instructions = navigationInstructions,
                            isOffRoute = false,
                        )
                    }
                }
            }
        }
    })
    LaunchedEffect(hasLocationPermission, isNavigationActive, navigationRoute, appLifecycleState) {
        if (!hasLocationPermission) return@LaunchedEffect
        if (!isNavigationActive && appLifecycleState != AppLifecycleState.RESUMED) return@LaunchedEffect
        while (isActive) {
            delay(1000L)
            timeoutTick()
        }
    }

    CompassUpdates(
        context = context,
        enabled = (appLifecycleState == AppLifecycleState.RESUMED),
    ) { heading ->
        onHeadingSensorFixChanged(true)
        onHeadingChanged(heading)
        if (isHeadingUp) {
            // 5km/h以上の走行中はGPS進行方向に従うため、低速・停止時のみ地磁気コンパスで回転
            if (speedKmh < SPEED_HEADING_THRESHOLD_KMH) {
                orientationAnimator.rotateTo(heading)
            }
        }
    }
}

// 停止時の不安定な GPS 方位を区間選択に使わない。
private const val PROGRESS_BEARING_MIN_SPEED_MPS = 1.5f
