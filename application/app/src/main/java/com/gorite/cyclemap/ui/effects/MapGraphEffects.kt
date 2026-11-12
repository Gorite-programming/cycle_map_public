package com.gorite.cyclemap.ui.effects

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.gorite.cyclemap.data.RoutingGraphSelection
import com.gorite.cyclemap.data.RoutingGraphSelector
import com.gorite.cyclemap.routing.loadTimingSink
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.tracking.LocationTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

@Composable
internal fun MapGraphEffects(
    context: Context,
    scope: CoroutineScope,
    routeJobRef: AtomicReference<RouteCalculationJob?>,
    inFlightRouteRef: AtomicReference<InFlightRouteRequest?>,
    routeExecutionGeneration: java.util.concurrent.atomic.AtomicLong,
    graphLock: ReentrantReadWriteLock,
    pendingRouteRef: AtomicReference<RouteRequest?>,
    switchRef: AtomicReference<((RoutingGraphSelection.Available) -> Unit)?>,
    mappedGraph: LazyMappedRoadGraph?,
    onMappedGraphChanged: (LazyMappedRoadGraph?) -> Unit,
    loadedGraphName: String?,
    onLoadedGraphNameChanged: (String?) -> Unit,
    isSwitchingGraph: Boolean,
    onSwitchingGraphChanged: (Boolean) -> Unit,
    onCalculatingRouteChanged: (Boolean) -> Unit,
    onReroutingChanged: (Boolean) -> Unit,
    onLongCalculationNoticeCleared: () -> Unit,
    onWarningMessageChanged: (String?) -> Unit,
    currentLocation: Location?,
    onLocationUpdate: ((Location) -> Unit)?,
    runRouteCalculation: (start: GeoPoint, goal: GeoPoint, isReroute: Boolean, waypointsOverride: List<GeoPoint>?, preferenceOverride: RoutePreference?) -> Unit,
    retryRouteCalculation: (request: RouteRequest, retriedAfterSwitch: Boolean) -> Unit,
    onGpxRecordingStarted: () -> Unit,
    onGpxRecordingEmpty: () -> Unit,
    onGpxRecordingSaved: (points: Int, fileName: String) -> Unit,
) {
    /**
     * 県グラフ切替。routing要求時 (runRouteCalculation) と現在地更新時 (先読み) から呼ばれる。
     */
    val startGraphSwitch: (RoutingGraphSelection.Available) -> Unit = switch@{ selection ->
        if (isSwitchingGraph || loadedGraphName == selection.fileName) return@switch
        val dataDir = cycleMapDataDir(context)
        val resolvedFiles = RoutingGraphSelector.resolveGraphFiles(dataDir, selection.fileName)
        if (resolvedFiles == null) {
            pendingRouteRef.set(null)
            android.util.Log.w("CycleMapGraph", "graph missing: ${selection.fileName} dir=${dataDir.absolutePath}")
            onWarningMessageChanged("${selection.prefectureName}のルートデータが端末に入っていません。メニュー「オフラインデータ」で確認できます")
            Log.e(
                "CycleMapRoute",
                "ROUTE_FAILURE reason=graph_not_available prefecture=${selection.prefectureName} file=${selection.fileName}",
            )
            return@switch
        }
        routeExecutionGeneration.incrementAndGet()
        val (graphFile, graphIndex) = resolvedFiles
        onSwitchingGraphChanged(true)
        onCalculatingRouteChanged(false)
        onReroutingChanged(false)
        onLongCalculationNoticeCleared()
        Log.i("CycleMapRoute", "GRAPH_SWITCH_BEGIN to=${selection.fileName} prefecture=${selection.prefectureName}")
        scope.launch {
            var interruptedRequest: InFlightRouteRequest? = null
            var wasInterrupted = false
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val activeJob = routeJobRef.getAndSet(null)
                    interruptedRequest = inFlightRouteRef.getAndSet(null)
                    wasInterrupted = activeJob != null
                    activeJob?.cancelAndJoin()
                    val t0 = SystemClock.elapsedRealtime()
                    loadTimingSink = { Log.i("CycleMapGraph", it) }
                    val g = LazyMappedRoadGraph.load(graphFile, graphIndex)
                    loadContractionHierarchy(g, graphFile, warmUp = true)
                    val elapsed = SystemClock.elapsedRealtime() - t0
                    Pair(g, elapsed)
                }
                val previous = loadedGraphName
                graphLock.write { onMappedGraphChanged(loaded.first) }
                onLoadedGraphNameChanged(selection.fileName)
                Log.i(
                    "CycleMapRoute",
                    "GRAPH_SWITCH from=${previous ?: "(none)"} to=${selection.fileName} " +
                        "prefecture=${selection.prefectureName} loadMs=${loaded.second} " +
                        "nodes=${loaded.first.nodeCount} edges=${loaded.first.edgeCount}",
                )
                pendingRouteRef.getAndSet(null)?.let { req ->
                    runRouteCalculation(req.start, req.goal, req.isReroute, req.waypoints, req.preference)
                    interruptedRequest = null
                }
                if (wasInterrupted && interruptedRequest != null) {
                    val interrupted = interruptedRequest!!
                    if (shouldRetryAfterGraphSwitch(interrupted)) {
                        retryRouteCalculation(interrupted.request, true)
                    } else {
                        onWarningMessageChanged("地図データの切替でルート計算が中断されました。もう一度お試しください")
                        Log.e("CycleMapRoute", "ROUTE_FAILURE reason=graph-switch-retry-exhausted")
                    }
                }
            } catch (t: Throwable) {
                interruptedRequest?.let { inFlightRouteRef.compareAndSet(it, null) }
                pendingRouteRef.set(null)
                android.util.Log.w("CycleMapGraph", "graph load failed: ${t.message}", t)
                onWarningMessageChanged("${selection.prefectureName}のルートデータを読み込めませんでした。データを入れ直してから、アプリを開き直してください")
                Log.e(
                    "CycleMapRoute",
                    "ROUTE_FAILURE reason=graph-load-failed file=${selection.fileName} " +
                        "exception=${t::class.java.simpleName}:${t.message}",
                )
            } finally {
                onSwitchingGraphChanged(false)
            }
        }
    }
    switchRef.set(startGraphSwitch)

    // Load graph & tile check (起動時: 配置済み最初のグラフ。現在地が他県なら後続の位置効果で切替)。
    LaunchedEffect(Unit) {
        val dataDir = cycleMapDataDir(context)
        configureOsmdroid(context, dataDir)
        val resolvedFiles = RoutingGraphSelector.findFirstAvailableGraph(dataDir)
        if (resolvedFiles == null) {
            android.util.Log.w("CycleMapGraph", "no graph in ${dataDir.absolutePath}")
            onWarningMessageChanged("ルートデータが端末に入っていません。メニュー「オフラインデータ」で確認できます")
            Log.e("CycleMapRoute", "GRAPH_SELECT startup loaded=false reason=missing dir=${dataDir.absolutePath}")
        } else {
            val (graphFile, graphIndex) = resolvedFiles
            onSwitchingGraphChanged(true)
            try {
                val result = withContext(Dispatchers.IO) {
                    val before = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    val start = SystemClock.elapsedRealtime()
                    loadTimingSink = { Log.i("CycleMapGraph", it) }
                    val graph = LazyMappedRoadGraph.load(graphFile, graphIndex)
                    loadContractionHierarchy(graph, graphFile, warmUp = true)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val after = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    Triple(graph, elapsed, after.totalPss - before.totalPss)
                }
                onMappedGraphChanged(result.first)
                onLoadedGraphNameChanged(graphFile.name)
                Log.i("CycleMapGraph", "mappedLoadMs=${result.second} pssDeltaKb=${result.third} nodes=${result.first.nodeCount} edges=${result.first.edgeCount}")
                Log.i("CycleMapRoute", "GRAPH_SELECT startup file=${graphFile.name} loaded=true nodes=${result.first.nodeCount}")
                // 読み込み中に頼まれたルート (起動直後に目的地を選んだ、開き直してルートを戻すなど) は保留されているので、ここで計算する。
                // 県の切替 (startGraphSwitch) では拾っていたが、起動時の読み込みでは拾っておらず、ルートが出ないままになっていた。
                // 県が違えば runRouteCalculation の中でその県へ切り替える。
                onSwitchingGraphChanged(false)
                pendingRouteRef.getAndSet(null)?.let { req ->
                    Log.i("CycleMapRoute", "ROUTE_PENDING_AFTER_STARTUP_LOAD goal=${req.goal.latitude},${req.goal.longitude}")
                    runRouteCalculation(req.start, req.goal, req.isReroute, req.waypoints, req.preference)
                }
            } catch (t: Throwable) {
                android.util.Log.w("CycleMapGraph", "graph load failed: ${t.message}", t)
                onWarningMessageChanged("ルートデータを読み込めませんでした。データを入れ直してから、アプリを開き直してください")
                Log.e("CycleMapRoute", "ROUTE_FAILURE reason=graph-load-failed file=${graphFile.name} exception=${t::class.java.simpleName}:${t.message}")
            } finally {
                onSwitchingGraphChanged(false)
            }
        }
    }

    // 現在地の県に応じた先読み切替
    LaunchedEffect(currentLocation) {
        val loc = currentLocation ?: return@LaunchedEffect
        onLocationUpdate?.invoke(loc)
        if (isSwitchingGraph) return@LaunchedEffect
        when (val sel = RoutingGraphSelector.select(loc.latitude, loc.longitude)) {
            is RoutingGraphSelection.Available -> {
                if (sel.fileName != loadedGraphName && (mappedGraph != null || loadedGraphName == null)) {
                    if (BuildConfig.DEBUG) {
                        Log.i(
                            "CycleMapRoute",
                            "GRAPH_SELECT location=${loc.latitude},${loc.longitude} prefecture=${sel.prefectureName} " +
                                "file=${sel.fileName} trigger=location-update",
                        )
                    }
                    switchRef.get()?.invoke(sel)
                }
            }
            is RoutingGraphSelection.Unavailable -> {
                // routing要求時に graph_not_available で明示失敗させる
            }
        }
    }

    DisposableEffect(mappedGraph) {
        val graphToDispose = mappedGraph
        onDispose {
            // CRIT-02 fix: 新グラフ切替直後に起動した新しい routeJob を onDispose が道連れキャンセルするのを防ぐため、
            // routeJobRef.cancel() は行わず、古いグラフの close() のみ実行する。
            // 進行中ルート探索のキャンセルは startGraphSwitch 冒頭および画面破棄時の DisposableEffect(Unit) で行う。
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                graphLock.write { graphToDispose?.close() }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            routeJobRef.getAndSet(null)?.cancel()
        }
    }

    // GPX BroadcastReceiver
    val currentOnGpxRecordingStarted by rememberUpdatedState(onGpxRecordingStarted)
    val currentOnGpxRecordingEmpty by rememberUpdatedState(onGpxRecordingEmpty)
    val currentOnGpxRecordingSaved by rememberUpdatedState(onGpxRecordingSaved)
    val currentOnLocationUpdate by rememberUpdatedState(onLocationUpdate)

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    LocationTrackingService.ACTION_RECORDING_STARTED -> {
                        currentOnGpxRecordingStarted()
                    }
                    LocationTrackingService.ACTION_RECORDING_EMPTY -> {
                        currentOnGpxRecordingEmpty()
                    }
                    LocationTrackingService.ACTION_RECORDING_SAVED -> {
                        val path = intent.getStringExtra(LocationTrackingService.EXTRA_PATH).orEmpty()
                        val points = intent.getIntExtra(LocationTrackingService.EXTRA_POINTS, 0)
                        val fileName = File(path).name
                        currentOnGpxRecordingSaved(points, fileName)
                    }
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(LocationTrackingService.ACTION_RECORDING_STARTED)
                addAction(LocationTrackingService.ACTION_RECORDING_EMPTY)
                addAction(LocationTrackingService.ACTION_RECORDING_SAVED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
}
