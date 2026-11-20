package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import android.content.Context
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.gorite.cyclemap.data.RoutingGraphSelection
import com.gorite.cyclemap.data.RoutingGraphSelector
import com.gorite.cyclemap.data.findPrefectureName
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.Maneuver
import com.gorite.cyclemap.routing.OffRouteDetector
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.routing.extractManeuvers
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import com.gorite.cyclemap.utils.cycleMapDataDir
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

internal class RouteExecutionContext(
    val context: Context,
    val routeJobRef: AtomicReference<RouteCalculationJob?>,
    val routeExecutionGeneration: AtomicLong,
    val inFlightRouteRef: AtomicReference<InFlightRouteRequest?>,
    val graphLock: ReentrantReadWriteLock,
    val pendingRouteRef: AtomicReference<RouteRequest?>,
    val getGraph: () -> LazyMappedRoadGraph?,
    val getLoadedGraphName: () -> String?,
    val isSwitchingGraph: () -> Boolean,
    val onSwitchGraph: (RoutingGraphSelection.Available) -> Unit,
    val getMapView: () -> MapView?,
    val getDestMarker: () -> Marker?,
    val getStartMarker: () -> Marker?,
    val getWaypointMarkers: () -> List<Marker>,
    val getRouteOverlay: () -> Polyline?,
    val getWaypoints: () -> List<GeoPoint>,
    val getRoutePreference: () -> RoutePreference,
    val offRouteDetector: OffRouteDetector,
    val onDestinationChanged: (GeoPoint) -> Unit,
    val onWaypointsChanged: (List<GeoPoint>) -> Unit,
    val onStartMarkerChanged: (Marker?) -> Unit,
    val onDestMarkerChanged: (Marker?) -> Unit,
    val onWaypointMarkersChanged: (List<Marker>) -> Unit,
    val onCalculatingRouteChanged: (Boolean) -> Unit,
    val onReroutingChanged: (Boolean) -> Unit,
    val onLongCalculationNoticeCleared: () -> Unit,
    val onWarningMessageChanged: (String?) -> Unit,
    val onRouteCalculated: (
        overlay: Polyline?,
        route: List<RoutePoint>,
        maneuvers: List<Maneuver>,
        instructions: List<RouteInstruction>,
        summary: RouteSummary,
        isReroute: Boolean,
        crossRouteElevation: CrossRouteElevationData?,
    ) -> Unit,
    val onRouteOptionsCalculated: ((List<com.gorite.cyclemap.contract.RouteOption>) -> Unit)? = null,
)

internal data class InFlightRouteRequest(
    val request: RouteRequest,
    val executionGeneration: Long,
    val retriedAfterSwitch: Boolean,
)

internal fun shouldRetryAfterGraphSwitch(request: InFlightRouteRequest?): Boolean =
    request != null && !request.retriedAfterSwitch

internal fun isCurrentRouteExecution(executionGeneration: Long, currentGeneration: Long): Boolean =
    executionGeneration == currentGeneration

internal fun executeRouteCalculation(
    env: RouteExecutionContext,
    start: GeoPoint,
    goal: GeoPoint,
    isReroute: Boolean,
    waypointsOverride: List<GeoPoint>?,
    preferenceOverride: RoutePreference?,
    retriedAfterSwitch: Boolean = false,
) {
    val requestStartedAt = SystemClock.elapsedRealtime()
    val executionGeneration = env.routeExecutionGeneration.incrementAndGet()
    env.routeJobRef.getAndSet(null)?.cancel()
    val startPrefecture = findPrefectureName(start.latitude, start.longitude)
    val goalPrefecture = findPrefectureName(goal.latitude, goal.longitude)
    val straightDistanceMeters = haversineMeters(
        start.latitude,
        start.longitude,
        goal.latitude,
        goal.longitude,
    )
    val noticeStops = listOf(start) + (waypointsOverride ?: env.getWaypoints()) + goal
    val notice = if (isReroute) {
        CrossPrefectureNotice(showLongCalculationNotice = false, showUnsupportedWarning = false)
    } else {
        crossPrefectureNotice(noticeStops.mapIndexed { i, point ->
            val pref = findPrefectureName(point.latitude, point.longitude)
            pref to if (i == 0) 0.0 else haversineMeters(
                noticeStops[i - 1].latitude, noticeStops[i - 1].longitude,
                point.latitude, point.longitude,
            )
        })
    }
    env.onCalculatingRouteChanged(true)
    if (notice.showLongCalculationNotice) {
        env.onWarningMessageChanged(LONG_CROSS_PREFECTURE_CALCULATION_MESSAGE)
        Log.i(
            "CycleMapRoute",
            "ROUTE_NOTICE kind=long_cross_prefecture straightKm=${"%.2f".format(java.util.Locale.US, straightDistanceMeters / 1_000.0)}",
        )
    }
    if (isReroute) {
        env.onReroutingChanged(true)
    }
    val graph = env.getGraph()
    if (BuildConfig.DEBUG) {
        Log.i(
            "CycleMapRoute",
            "ROUTE_REQUEST isReroute=$isReroute start=${start.latitude},${start.longitude} " +
                "goal=${goal.latitude},${goal.longitude} graphLoaded=${graph != null}",
        )
    }
    when (val selection = RoutingGraphSelector.select(start.latitude, start.longitude)) {
        is RoutingGraphSelection.Unavailable -> {
            env.onCalculatingRouteChanged(false)
            env.onReroutingChanged(false)
            env.onLongCalculationNoticeCleared()
            val prefLabel = selection.prefectureName ?: "判定不能地域"
            env.onWarningMessageChanged("${prefLabel}のルートグラフがありません")
            Log.e("CycleMapRoute", "ROUTE_FAILURE reason=graph_not_available prefecture=$prefLabel")
            return
        }
        is RoutingGraphSelection.Available -> {
            val goalPref = goalPrefecture
            if (BuildConfig.DEBUG) {
                Log.i(
                    "CycleMapRoute",
                    "GRAPH_SELECT location=${start.latitude},${start.longitude} " +
                        "prefecture=${selection.prefectureName} file=${selection.fileName} " +
                        "loaded=${env.getLoadedGraphName()} goalPrefecture=${goalPref ?: "(unknown)"}",
                )
            }
            if (env.getLoadedGraphName() != selection.fileName || graph == null) {
                env.pendingRouteRef.set(RouteRequest(start, goal, isReroute, waypointsOverride, preferenceOverride))
                if (!env.isSwitchingGraph()) env.onSwitchGraph(selection)
                env.onCalculatingRouteChanged(false)
                env.onReroutingChanged(false)
                env.onLongCalculationNoticeCleared()
                env.onWarningMessageChanged("${selection.prefectureName}のグラフに切替中です")
                Log.i(
                    "CycleMapRoute",
                    "ROUTE_FAILURE reason=graph-switching prefecture=${selection.prefectureName} " +
                        "file=${selection.fileName}",
                )
                return
            }
        }
    }

    if (!isReroute) {
        env.onDestinationChanged(goal)
    }
    if (waypointsOverride != null) {
        env.onWaypointsChanged(waypointsOverride)
    }

    val currentWaypoints = waypointsOverride ?: env.getWaypoints()
    val currentPreference = preferenceOverride ?: env.getRoutePreference()
    if (!isReroute) {
        (env.context as? ComponentActivity)?.runOnUiThread {
            val view = env.getMapView() ?: return@runOnUiThread
            env.getDestMarker()?.let { view.overlays.remove(it) }
            env.getStartMarker()?.let { view.overlays.remove(it) }
            env.getWaypointMarkers().forEach { view.overlays.remove(it) }
            val startM = Marker(view).apply {
                position = start
                title = "スタート"
                icon = startPointDrawable(env.context)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            view.overlays.add(startM)
            env.onStartMarkerChanged(startM)

            val wpMarkers = currentWaypoints.mapIndexed { idx, wp ->
                Marker(view).apply {
                    position = wp
                    title = "経由地 ${idx + 1}"
                    icon = waypointDrawable(env.context, idx + 1)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
            }
            wpMarkers.forEach { view.overlays.add(it) }
            env.onWaypointMarkersChanged(wpMarkers)

            val destM = Marker(view).apply {
                position = goal
                title = "目的地"
                icon = ContextCompat.getDrawable(env.context, R.drawable.ic_destination)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            view.overlays.add(destM)
            env.onDestMarkerChanged(destM)
            view.controller.animateTo(goal)
            view.invalidate()
        }
    }

    val routeJob = RouteCalculationJob()
    env.routeJobRef.set(routeJob)
    val inFlightRequest = InFlightRouteRequest(
        RouteRequest(start, goal, isReroute, currentWaypoints, currentPreference),
        executionGeneration,
        retriedAfterSwitch,
    )
    env.inFlightRouteRef.set(inFlightRequest)
    val jobGraphName = env.getLoadedGraphName()
    val jobGraph = graph
    routeJob.job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        try {
            if (routeJob.isCancelled()) return@launch

            val stops = ArrayList<GeoPoint>().apply {
                add(start)
                addAll(currentWaypoints)
                add(goal)
            }
            val stopCoords = stops.map { it.latitude to it.longitude }
            var isAnyTruncated = false
            var firstStartIndex = -1
            var lastGoalIndex = -1
            var usedCrossRoute = false
            var crossOutcome: CrossPlanningOutcome = CrossPlanningOutcome.NotNeeded
            var crossInstructions: List<RouteInstruction>? = null
            var crossElevation: CrossRouteElevationData? = null
            var crossLoadMs = 0L
            var crossTotalMs = 0L
            val legEngines = LinkedHashSet<String>()
            var plannedCrossRoute: com.gorite.cyclemap.routing.MappedRouteResult? = null
            var singleGraphResult: com.gorite.cyclemap.routing.MappedRouteResult? = null
            var singleGraphWaypointFailure: Int? = null
            var waypointNodeIds: List<Long> = emptyList()

            val pref = currentPreference

            val needsCrossPlanning = stops.zipWithNext().any { (a, b) ->
                classifyLeg(findPrefectureName(a.latitude, a.longitude), findPrefectureName(b.latitude, b.longitude)) == CrossLegKind.CROSS
            }
            if (needsCrossPlanning && !routeJob.isCancelled()) {
                val crossStarted = SystemClock.elapsedRealtime()
                try {
                    val outcome = jobGraph?.let { graphForJob ->
                        calculateMultiLegCrossRoute(
                            env.context, env.graphLock, graphForJob, jobGraphName, stops, pref,
                            isCancelled = { routeJob.isCancelled() || env.routeExecutionGeneration.get() != executionGeneration },
                        )
                    } ?: MultiLegCrossOutcome.Failed
                    when (outcome) {
                        is MultiLegCrossOutcome.Success -> {
                            val planned = outcome.result
                            usedCrossRoute = true
                            crossOutcome = CrossPlanningOutcome.Succeeded
                            crossLoadMs = planned.loadMs
                            crossInstructions = planned.instructions
                            crossElevation = planned.elevation
                            plannedCrossRoute = planned.route
                            isAnyTruncated = planned.route.wasTruncated
                            crossTotalMs = SystemClock.elapsedRealtime() - crossStarted
                            if (currentWaypoints.isEmpty() && planned.chain.split('>').size == 2) {
                                Log.i("CycleMapRoute", "ROUTE_TIMING cross loadMs=$crossLoadMs portalMs=${planned.portalMs} aMs=${planned.aMs} bMs=${planned.bMs} totalMs=$crossTotalMs")
                            } else {
                                Log.i("CycleMapRoute", "ROUTE_TIMING cross legs=${stops.size - 1} chain=${planned.chain} loadMs=$crossLoadMs totalMs=$crossTotalMs")
                            }
                            Log.i("CycleMapRoute", "ROUTE_NOTICE kind=cross_route_used from=$startPrefecture to=$goalPrefecture via=${planned.chain}")
                        }
                        is MultiLegCrossOutcome.Cancelled -> return@launch
                        is MultiLegCrossOutcome.MissingPrefectureData -> {
                            crossOutcome = CrossPlanningOutcome.MissingPrefectureData(outcome.missingPrefectures)
                            Log.w("CycleMapRoute", "CROSS_PREFECTURE missing=${outcome.missingPrefectures.joinToString(",")}")
                        }
                        is MultiLegCrossOutcome.WaypointUnreachable -> {
                            crossOutcome = CrossPlanningOutcome.WaypointUnreachable(outcome.waypointNumber)
                            Log.w("CycleMapRoute", "CROSS_PREFECTURE waypoint_unreachable=${outcome.waypointNumber}")
                        }
                        is MultiLegCrossOutcome.NotCrossPrefecture -> {
                            crossOutcome = CrossPlanningOutcome.NotNeeded
                        }
                        is MultiLegCrossOutcome.Failed -> {
                            crossOutcome = CrossPlanningOutcome.Failed
                        }
                    }
                } catch (t: Throwable) {
                    if (routeJob.isCancelled()) return@launch
                    crossOutcome = CrossPlanningOutcome.Failed
                    Log.w("CycleMapRoute", "CROSS_PREFECTURE fallback reason=${t::class.java.simpleName}:${t.message}")
                }
            }

            if (shouldAttemptSingleGraphFallback(isReroute, usedCrossRoute, crossOutcome, needsCrossPlanning)) {
                if (routeJob.isCancelled()) return@launch
                val plan = env.graphLock.read {
                    if (routeJob.isCancelled()) return@read SingleGraphRoutePlan.Cancelled
                    val t0 = SystemClock.elapsedRealtime()
                    val planned = planSingleGraphRoute(
                        graph = graph,
                        stops = stopCoords,
                        preference = pref,
                        isCancelled = { routeJob.isCancelled() },
                        maxExpandedNodes = ROUTE_MAX_EXPANDED_NODES,
                    )
                    Log.i("CycleMapRoute", "ROUTE_TIMING single_graph legs=${stops.size - 1} searchMs=${SystemClock.elapsedRealtime() - t0} expanded=${graph.lastExpandedNodes} engine=${graph.lastEngine} chQueryMs=${graph.lastChQueryMillis}")
                    planned
                }
                when (plan) {
                    is SingleGraphRoutePlan.Cancelled -> return@launch
                    is SingleGraphRoutePlan.Truncated -> isAnyTruncated = true
                    is SingleGraphRoutePlan.WaypointUnreachable -> singleGraphWaypointFailure = plan.waypointNumber
                    is SingleGraphRoutePlan.Unreachable -> Unit
                    is SingleGraphRoutePlan.Success -> {
                        singleGraphResult = plan.result
                        firstStartIndex = plan.firstStartIndex
                        lastGoalIndex = plan.lastGoalIndex
                        legEngines += plan.legEngines
                        waypointNodeIds = plan.waypointNodeIds
                        if (plan.result.wasTruncated) isAnyTruncated = true
                    }
                }
            }

            val rawResult = plannedCrossRoute ?: singleGraphResult ?: com.gorite.cyclemap.routing.MappedRouteResult(
                nodeIds = emptyList(),
                totalDistanceMeters = 0.0,
                totalCost = 0.0,
                coordinates = emptyList(),
                wasCancelled = false,
                wasTruncated = isAnyTruncated,
                nodeIndices = emptyList(),
            )
            // 起点・終点を最寄りのノードではなく最初・最後の辺に射影して、端まで行って戻る遠回りを除く。
            // 県またぎの経路は案内 (routeIndex) を別に組み立てているので対象外。
            val endTrim = if (!usedCrossRoute && rawResult.isReachable && stops.size >= 2) {
                trimSingleGraphRouteSafe(rawResult, stopCoords, waypointNodeIds)
            } else null
            val result = endTrim?.result ?: rawResult
            if (endTrim != null && endTrim.result !== rawResult) {
                Log.i("CycleMapRoute", "ROUTE_ENDTRIM startTrim=${endTrim.head.isNotEmpty()} goalTrim=${endTrim.tail.isNotEmpty()} nodes=${rawResult.nodeIds.size}->${result.nodeIds.size} distM=${rawResult.totalDistanceMeters.toInt()}->${result.totalDistanceMeters.toInt()}")
            }
            if (routeJob.isCancelled()) return@launch
            if (result.wasCancelled) return@launch

            val completeness = classifyRouteCompleteness(
                needsCrossPlanning = needsCrossPlanning,
                usedCrossRoute = usedCrossRoute,
                singleGraphReachable = singleGraphResult?.isReachable == true,
            )
            val decision = decideRouteApplication(
                isReroute = isReroute,
                completeness = completeness,
                crossOutcome = crossOutcome,
                wasTruncated = result.wasTruncated,
                singleGraphWaypointFailure = singleGraphWaypointFailure,
            )

            Log.i(
                "CycleMapRoute",
                "ROUTE_RESULT pref=$pref success=${result.isReachable} truncated=${result.wasTruncated} " +
                    "routeNodeCount=${result.nodeIds.size} routeDistanceM=${result.totalDistanceMeters.toInt()} " +
                    "routeCost=${"%.2f".format(java.util.Locale.US, result.totalCost)} pathHash=${result.nodeIds.hashCode()} " +
                    "goal=${stops.last().latitude},${stops.last().longitude} " +
                    "engine=${if (usedCrossRoute) "astar" else legEngines.joinToString("+").ifEmpty { "astar" }}",
            )
            val richInstructions = when {
                !decision.shouldReplaceRoute -> emptyList()
                crossInstructions != null -> crossInstructions
                else -> env.graphLock.read {
                    if (routeJob.isCancelled()) return@read emptyList<RouteInstruction>()
                    try {
                        buildRouteInstructions(graph, result.nodeIds, result.coordinates)
                    } catch (t: Throwable) {
                        Log.w("CycleMapInstruction", "rich instruction build failed, using maneuvers", t)
                        emptyList()
                    }
                }
            }
            // 表示・追従用の線は、射影点 (起点側・終点側) を端に足したもの。案内は最初のノードからの距離で
            // 組み立てているので、射影点から最初のノードまでの距離だけ後ろへずらす。
            // 起点側: 利用者の位置 → 射影点 → 最初のノード。終点側: 最後のノード → 射影点 → 目的地 (どちらも道から 200 m 以内のときだけ)。
            fun pathLength(points: List<Pair<Double, Double>>): Double =
                points.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }
            val first = result.coordinates.firstOrNull()
            val last = result.coordinates.lastOrNull()
            val headPoints: List<Pair<Double, Double>> = if (first == null || !rawResult.isReachable || usedCrossRoute) emptyList() else {
                val seed = endTrim?.head?.takeIf { it.isNotEmpty() }
                    ?: listOf(stops.first().latitude to stops.first().longitude)
                if (pathLength(seed + first) in 3.0..200.0) seed else emptyList()
            }
            val tailPoints: List<Pair<Double, Double>> = if (last == null || !rawResult.isReachable || usedCrossRoute) emptyList() else {
                val seed = endTrim?.tail?.takeIf { it.isNotEmpty() }
                    ?: listOf(stops.last().latitude to stops.last().longitude)
                if (pathLength(listOf(last) + seed) in 3.0..200.0) seed else emptyList()
            }
            val lineCoordinates = headPoints + result.coordinates + tailPoints
            val headLen = if (first != null && headPoints.isNotEmpty()) pathLength(headPoints + first) else 0.0
            val instructionsForRoute = if (headLen > 0.0) {
                richInstructions.map { it.copy(distanceFromStartMeters = it.distanceFromStartMeters + headLen) }
            } else richInstructions

            // 線の距離と案内の組立ては UI スレッドへ戻る前に済ませる。
            val navigationRoute = lineCoordinates.map { (lat, lon) -> RoutePoint(lat, lon) }
            val navigationManeuvers = extractManeuvers(navigationRoute)
            val routeSummary = RouteSummary(displayedRouteDistance(lineCoordinates), result.nodeIds.size - 1)

            val primaryDisplayed = CompletableDeferred<Boolean>()
            val activityForDisplay = env.context as? ComponentActivity ?: return@launch
            activityForDisplay.runOnUiThread {
                if (routeJob.isCancelled() || !isCurrentRouteExecution(executionGeneration, env.routeExecutionGeneration.get()) || env.routeJobRef.get() !== routeJob) {
                    env.inFlightRouteRef.compareAndSet(inFlightRequest, null)
                    primaryDisplayed.complete(false)
                    return@runOnUiThread
                }
                if (env.getLoadedGraphName() != jobGraphName || env.getGraph() !== jobGraph) {
                    Log.e("CycleMapRoute", "ROUTE_FAILURE reason=graph-changed jobGraph=$jobGraphName now=${env.getLoadedGraphName()}")
                    env.onCalculatingRouteChanged(false)
                    env.onReroutingChanged(false)
                    env.routeJobRef.compareAndSet(routeJob, null)
                    primaryDisplayed.complete(false)
                    return@runOnUiThread
                }
                if (!decision.shouldReplaceRoute) {
                    val reason = when {
                        result.wasTruncated -> "truncated-expanded-nodes"
                        crossOutcome is CrossPlanningOutcome.WaypointUnreachable || singleGraphWaypointFailure != null -> "waypoint-unreachable"
                        crossOutcome is CrossPlanningOutcome.MissingPrefectureData -> "missing-prefecture-data"
                        completeness == RouteCompleteness.PARTIAL -> "partial-route-rejected"
                        else -> "unreachable"
                    }
                    Log.e("CycleMapRoute", "ROUTE_FAILURE reason=$reason isReroute=$isReroute")
                    decision.warningMessage?.let { env.onWarningMessageChanged(it) }
                    env.onCalculatingRouteChanged(false)
                    env.onReroutingChanged(false)
                    env.routeJobRef.compareAndSet(routeJob, null)
                    primaryDisplayed.complete(false)
                    return@runOnUiThread
                }
                val activity = env.context as? ComponentActivity
                if (activity?.isFinishing == true || activity?.isDestroyed == true) {
                    Log.e(
                        "CycleMapRoute",
                        "ROUTE_FAILURE reason=activity-not-ready finishing=${activity?.isFinishing} destroyed=${activity?.isDestroyed}",
                    )
                    env.onCalculatingRouteChanged(false)
                    env.onReroutingChanged(false)
                    env.routeJobRef.compareAndSet(routeJob, null)
                    primaryDisplayed.complete(false)
                    return@runOnUiThread
                }
                env.onCalculatingRouteChanged(false)
                env.onReroutingChanged(false)
                env.onWarningMessageChanged(null)
                env.routeJobRef.compareAndSet(routeJob, null)

                val view = env.getMapView()
                val polyline: Polyline? = if (view != null && view.isAttachedToWindow) {
                    env.getRouteOverlay()?.let { view.overlays?.remove(it) }
                    Polyline(view).apply {
                        setPoints(lineCoordinates.map { (lat, lon) -> GeoPoint(lat, lon) })
                        outlinePaint.color = android.graphics.Color.parseColor("#333333")
                        outlinePaint.strokeWidth = 24f
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.isAntiAlias = true
                    }.also {
                        view.overlays.add(it)
                        view.invalidate()
                    }
                } else {
                    null
                }


                // 主経路を表示した時点では代替ルート未計算。古い候補をチップ表示しない。
                if (!isReroute) env.onRouteOptionsCalculated?.invoke(emptyList())

                env.onRouteCalculated(
                    polyline,
                    navigationRoute,
                    navigationManeuvers,
                    instructionsForRoute,
                    routeSummary,
                    isReroute,
                    if (usedCrossRoute) crossElevation else null,
                )

                decision.warningMessage?.let { warning ->
                    env.onWarningMessageChanged(warning)
                    val kind = when {
                        usedCrossRoute -> "cross_route_guidance"
                        completeness == RouteCompleteness.PARTIAL -> "cross_prefecture_unsupported"
                        else -> "route_notice"
                    }
                    Log.i("CycleMapRoute", "ROUTE_NOTICE kind=$kind")
                }

                Log.i(
                    "CycleMapRoute",
                    "ROUTE_TIMING firstDisplayMs=${SystemClock.elapsedRealtime() - requestStartedAt}",
                )

                Log.i(
                    "CycleMapRoute",
                    "ROUTE_DISPLAYED graph=$jobGraphName polylinePointCount=${result.coordinates.size} " +
                        "instructionCount=${richInstructions.size} maneuverCount=${navigationManeuvers.size} " +
                        "routeDisplayed=true",
                )
                primaryDisplayed.complete(true)
            }

            if (!primaryDisplayed.await()) {
                Log.i("CycleMapRoute", "ROUTE_TIMING optionsMs=0 options=0")
                return@launch
            }

            // UIへの主経路反映をrunOnUiThreadに登録した後、同じIOコルーチンで候補を計算する。
            // 世代は新規要求・reroute・clearで変わるため、表示後にrouteJobRefがnullでも古い候補を止められる。
            if (completeness == RouteCompleteness.COMPLETE && !usedCrossRoute && !isReroute && result.isReachable && !routeJob.isCancelled() &&
                env.routeExecutionGeneration.get() == executionGeneration
            ) {
                val optionsStartedAt = SystemClock.elapsedRealtime()
                val routeOptions = env.graphLock.read {
                    if (routeJob.isCancelled() || env.routeExecutionGeneration.get() != executionGeneration ||
                        env.getLoadedGraphName() != jobGraphName || env.getGraph() !== jobGraph
                    ) emptyList()
                    else {
                        val planner = com.gorite.cyclemap.real.RealRoutePlanner(graphProvider = { jobGraph })
                        planner.buildOptionsFromPrimary(
                            lazyGraph = jobGraph,
                            startIndex = if (currentWaypoints.isEmpty()) firstStartIndex else -1,
                            goalIndex = if (currentWaypoints.isEmpty()) lastGoalIndex else -1,
                            primaryResult = result,
                            preference = pref,
                            primaryDisplayPoints = lineCoordinates,
                            primaryNodePointOffset = headPoints.size,
                            travelMode = com.gorite.cyclemap.contract.TravelModeType.Bicycle,
                            isCancelled = {
                                routeJob.isCancelled() || env.routeExecutionGeneration.get() != executionGeneration ||
                                    env.getLoadedGraphName() != jobGraphName || env.getGraph() !== jobGraph
                            },
                            maxExpandedNodes = ROUTE_MAX_EXPANDED_NODES,
                        )
                    }
                }
                Log.i("CycleMapRoute", "ROUTE_TIMING optionsMs=${SystemClock.elapsedRealtime() - optionsStartedAt} options=${routeOptions.size}")
                if (routeOptions.isNotEmpty() && !routeJob.isCancelled() &&
                    env.routeExecutionGeneration.get() == executionGeneration &&
                    env.getLoadedGraphName() == jobGraphName && env.getGraph() === jobGraph
                ) {
                    (env.context as? ComponentActivity)?.runOnUiThread {
                        if (routeJob.isCancelled() || env.routeExecutionGeneration.get() != executionGeneration ||
                            env.getLoadedGraphName() != jobGraphName || env.getGraph() !== jobGraph
                        ) return@runOnUiThread
                        env.onRouteOptionsCalculated?.invoke(routeOptions)
                    }
                }
            } else {
                // rerouteでは候補探索をしない。従来のタイミングログは0件で維持する。
                Log.i("CycleMapRoute", "ROUTE_TIMING optionsMs=0 options=0")
            }
        } catch (t: Throwable) {
            if (routeJob.isCancelled()) return@launch
            if (t is OutOfMemoryError) {
                Log.e("CycleMapRoute", "ROUTE_FAILURE reason=out_of_memory")
            } else {
                Log.e("CycleMapGraph", "route failed", t)
                Log.e("CycleMapRoute", "ROUTE_FAILURE reason=exception exception=${t::class.java.simpleName}:${t.message}")
            }
            (env.context as? ComponentActivity)?.runOnUiThread {
                if (routeJob.isCancelled() || env.routeJobRef.get() !== routeJob) return@runOnUiThread
                env.onWarningMessageChanged(routeFailureMessage(t is OutOfMemoryError))
                env.onCalculatingRouteChanged(false)
                env.onReroutingChanged(false)
                env.routeJobRef.compareAndSet(routeJob, null)
            }
        } finally {
            if (isCurrentRouteExecution(executionGeneration, env.routeExecutionGeneration.get())) {
                env.inFlightRouteRef.compareAndSet(inFlightRequest, null)
                (env.context as? ComponentActivity)?.runOnUiThread {
                    env.onLongCalculationNoticeCleared()
                }
            }
            (env.context as? ComponentActivity)?.runOnUiThread {
                if (env.routeJobRef.get() === routeJob) {
                    env.routeJobRef.compareAndSet(routeJob, null)
                    env.onCalculatingRouteChanged(false)
                    env.onReroutingChanged(false)
                }
            }
        }
    }
}
