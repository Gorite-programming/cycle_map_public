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
import com.gorite.cyclemap.routing.routeWithSnapFallback
import com.gorite.cyclemap.routing.portalsBetween
import com.gorite.cyclemap.routing.routeAcrossGraphs
import com.gorite.cyclemap.routing.segmentGradesAlongIndices
import com.gorite.cyclemap.routing.nodeElevationsAt
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

internal fun executeRouteCalculation(
    env: RouteExecutionContext,
    start: GeoPoint,
    goal: GeoPoint,
    isReroute: Boolean,
    waypointsOverride: List<GeoPoint>?,
    preferenceOverride: RoutePreference?,
) {
    val requestStartedAt = SystemClock.elapsedRealtime()
    val executionGeneration = env.routeExecutionGeneration.incrementAndGet()
    env.routeJobRef.getAndSet(null)?.cancel()
    if (!isReroute) {
        env.onDestinationChanged(goal)
    }
    if (waypointsOverride != null) {
        env.onWaypointsChanged(waypointsOverride)
    }
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

    val currentWaypoints = waypointsOverride ?: env.getWaypoints()
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
            val allNodeIds = ArrayList<Long>()
            val allNodeIndices = ArrayList<Int>()
            val allCoordinates = ArrayList<Pair<Double, Double>>()
            var totalDist = 0.0
            var totalCostVal = 0.0
            var isAllReachable = true
            var isAnyTruncated = false
            var firstStartIndex = -1
            var lastGoalIndex = -1
            var usedCrossRoute = false
            var crossInstructions: List<RouteInstruction>? = null
            var crossElevation: CrossRouteElevationData? = null
            var crossLoadMs = 0L
            var crossPortalMs = 0L
            var crossAMs = 0L
            var crossBMs = 0L
            var crossTotalMs = 0L

            val pref = preferenceOverride ?: env.getRoutePreference()

            val needsCrossPlanning = stops.zipWithNext().any { (a, b) ->
                classifyLeg(findPrefectureName(a.latitude, a.longitude), findPrefectureName(b.latitude, b.longitude)) == CrossLegKind.CROSS
            }
            if (needsCrossPlanning && !routeJob.isCancelled()) {
                val crossStarted = SystemClock.elapsedRealtime()
                try {
                    val planned = jobGraph?.let { graphForJob -> calculateMultiLegCrossRoute(
                        env.context, env.graphLock, graphForJob, jobGraphName, stops, pref,
                        isCancelled = { routeJob.isCancelled() || env.routeExecutionGeneration.get() != executionGeneration },
                    ) }
                    if (planned != null) {
                        usedCrossRoute = true
                        crossLoadMs = planned.loadMs
                        crossInstructions = planned.instructions
                        crossElevation = planned.elevation
                        totalDist = planned.route.totalDistanceMeters
                        totalCostVal = planned.route.totalCost
                        allNodeIds.addAll(planned.route.nodeIds)
                        allCoordinates.addAll(planned.route.coordinates)
                        isAnyTruncated = planned.route.wasTruncated
                        crossTotalMs = SystemClock.elapsedRealtime() - crossStarted
                        if (currentWaypoints.isEmpty() && planned.chain.split('>').size == 2) {
                            Log.i("CycleMapRoute", "ROUTE_TIMING cross loadMs=$crossLoadMs portalMs=${planned.portalMs} aMs=${planned.aMs} bMs=${planned.bMs} totalMs=$crossTotalMs")
                        } else {
                            Log.i("CycleMapRoute", "ROUTE_TIMING cross legs=${stops.size - 1} chain=${planned.chain} loadMs=$crossLoadMs totalMs=$crossTotalMs")
                        }
                        Log.i("CycleMapRoute", "ROUTE_NOTICE kind=cross_route_used from=$startPrefecture to=$goalPrefecture via=${planned.chain}")
                    }
                } catch (t: Throwable) {
                    if (routeJob.isCancelled()) return@launch
                    Log.w("CycleMapRoute", "CROSS_PREFECTURE fallback reason=${t::class.java.simpleName}:${t.message}")
                }
            }
            for (i in 0 until stops.size - 1) {
                if (usedCrossRoute) break
                if (routeJob.isCancelled()) return@launch
                val legStart = stops[i]
                val legGoal = stops[i + 1]
                val legResult = env.graphLock.read {
                    if (routeJob.isCancelled()) return@launch
                    val t0 = SystemClock.elapsedRealtime()
                    // 最寄りのノードで引けない (公園内などの孤立した道に当たった) ときだけ、次に近い候補で引き直す
                    val outcome = graph.routeWithSnapFallback(
                        legStart.latitude, legStart.longitude,
                        legGoal.latitude, legGoal.longitude,
                        isCancelled = { routeJob.isCancelled() },
                        maxExpandedNodes = ROUTE_MAX_EXPANDED_NODES,
                        preference = pref,
                    )
                    if (i == 0) firstStartIndex = outcome.startIndex
                    if (i == stops.size - 2) lastGoalIndex = outcome.goalIndex
                    Log.i(
                        "CycleMapRoute",
                        "ROUTE_SNAP leg=$i startNode=${outcome.startIndex} goalNode=${outcome.goalIndex} pref=$pref " +
                            "fallback=${outcome.usedFallbackSnap} attempts=${outcome.attempts}",
                    )
                    Log.i("CycleMapRoute", "ROUTE_TIMING leg=$i searchMs=${SystemClock.elapsedRealtime() - t0} expanded=${graph.lastExpandedNodes}")
                    outcome.result
                }
                if (routeJob.isCancelled() || legResult.wasCancelled) return@launch
                if (!legResult.isReachable) {
                    isAllReachable = false
                    break
                }
                if (legResult.wasTruncated) isAnyTruncated = true
                totalDist += legResult.totalDistanceMeters
                totalCostVal += legResult.totalCost

                if (allCoordinates.isEmpty()) {
                    allCoordinates.addAll(legResult.coordinates)
                    allNodeIds.addAll(legResult.nodeIds)
                    allNodeIndices.addAll(legResult.nodeIndices)
                } else {
                    if (legResult.coordinates.isNotEmpty()) {
                        allCoordinates.addAll(legResult.coordinates.drop(1))
                    }
                    if (legResult.nodeIds.isNotEmpty()) {
                        allNodeIds.addAll(legResult.nodeIds.drop(1))
                        allNodeIndices.addAll(legResult.nodeIndices.drop(1))
                    }
                }
            }

            val result = com.gorite.cyclemap.routing.MappedRouteResult(
                nodeIds = if (isAllReachable) allNodeIds else emptyList(),
                totalDistanceMeters = totalDist,
                totalCost = totalCostVal,
                coordinates = if (isAllReachable) allCoordinates else emptyList(),
                wasCancelled = false,
                wasTruncated = isAnyTruncated,
                // 番号の列は全区間そろっているときだけ渡す (欠けていれば座標から引く従来の処理になる)
                nodeIndices = if (isAllReachable && allNodeIndices.size == allNodeIds.size) allNodeIndices else emptyList(),
            )
            if (routeJob.isCancelled()) return@launch
            if (result.wasCancelled) return@launch

            Log.i(
                "CycleMapRoute",
                "ROUTE_RESULT pref=$pref success=${result.isReachable} truncated=${result.wasTruncated} " +
                    "routeNodeCount=${result.nodeIds.size} routeDistanceM=${result.totalDistanceMeters.toInt()} " +
                    "routeCost=${"%.2f".format(java.util.Locale.US, result.totalCost)} pathHash=${result.nodeIds.hashCode()}",
            )
            val richInstructions = crossInstructions ?: env.graphLock.read {
                if (routeJob.isCancelled()) return@read emptyList<RouteInstruction>()
                try {
                    buildRouteInstructions(graph, result.nodeIds, result.coordinates)
                } catch (t: Throwable) {
                    Log.w("CycleMapInstruction", "rich instruction build failed, using maneuvers", t)
                    emptyList()
                }
            }

            val primaryDisplayed = CompletableDeferred<Boolean>()
            val activityForDisplay = env.context as? ComponentActivity ?: return@launch
            activityForDisplay.runOnUiThread {
                if (routeJob.isCancelled() || env.routeExecutionGeneration.get() != executionGeneration || env.routeJobRef.get() !== routeJob) {
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
                if (result.wasTruncated) {
                    Log.e("CycleMapRoute", "ROUTE_FAILURE reason=truncated-expanded-nodes")
                    env.onWarningMessageChanged("探索範囲が大きすぎて打ち切りました。近めの目的地でお試しください")
                    env.onCalculatingRouteChanged(false)
                    env.onReroutingChanged(false)
                    env.routeJobRef.compareAndSet(routeJob, null)
                    primaryDisplayed.complete(false)
                    return@runOnUiThread
                }
                if (!result.isReachable) {
                    Log.e("CycleMapRoute", "ROUTE_FAILURE reason=unreachable isReroute=$isReroute")
                    env.onWarningMessageChanged(
                        if (isReroute) {
                            "再探索で有効なルートが見つかりませんでした。元のルートを維持します"
                        } else {
                            "有効な自転車ルートが見つかりませんでした。出発地や目的地が道路から離れている、または地図データの範囲外の可能性があります"
                        },
                    )
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
                        setPoints(result.coordinates.map { (lat, lon) -> GeoPoint(lat, lon) })
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

                val navigationRoute = result.coordinates.map { (lat, lon) -> RoutePoint(lat, lon) }
                val navigationManeuvers = extractManeuvers(navigationRoute)
                val routeSummary = RouteSummary(result.totalDistanceMeters, result.nodeIds.size - 1)

                // 主経路を表示した時点では代替ルート未計算。古い候補をチップ表示しない。
                if (!isReroute) env.onRouteOptionsCalculated?.invoke(emptyList())

                env.onRouteCalculated(
                    polyline,
                    navigationRoute,
                    navigationManeuvers,
                    richInstructions,
                    routeSummary,
                    isReroute,
                    if (usedCrossRoute) crossElevation else null,
                )

                if (usedCrossRoute) {
                    env.onWarningMessageChanged(CROSS_PREFECTURE_ROUTE_MESSAGE)
                    Log.i("CycleMapRoute", "ROUTE_NOTICE kind=cross_route_guidance")
                } else if (notice.showUnsupportedWarning) {
                    env.onWarningMessageChanged(CROSS_PREFECTURE_UNSUPPORTED_MESSAGE)
                    Log.i("CycleMapRoute", "ROUTE_NOTICE kind=cross_prefecture_unsupported")
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
            if (!usedCrossRoute && !isReroute && result.isReachable && !routeJob.isCancelled() &&
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
            Log.e("CycleMapGraph", "route failed", t)
            Log.e("CycleMapRoute", "ROUTE_FAILURE reason=exception exception=${t::class.java.simpleName}:${t.message}")
            (env.context as? ComponentActivity)?.runOnUiThread {
                if (routeJob.isCancelled() || env.routeJobRef.get() !== routeJob) return@runOnUiThread
                env.onWarningMessageChanged("ルート探索失敗: ${t.message}")
                env.onCalculatingRouteChanged(false)
                env.onReroutingChanged(false)
                env.routeJobRef.compareAndSet(routeJob, null)
            }
        } finally {
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
