package com.gorite.cyclemap.navigation

import kotlinx.coroutines.launch

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import android.animation.ValueAnimator
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.osmdroid.views.MapView
import com.gorite.cyclemap.contract.TurnType
import com.gorite.cyclemap.routing.IconResolver
import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.ManeuverType
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.routing.TurnClassifier
import com.gorite.cyclemap.routing.bearingBetween
import com.gorite.cyclemap.tracking.GpsSignalStatus
import com.gorite.cyclemap.tracking.LocationTrackingService
import com.gorite.cyclemap.tracking.locationServiceConnection
import com.gorite.cyclemap.ui.map.toGuidanceTurnType
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// ナビ案内バナー用データ
// ---------------------------------------------------------------------------

/** 案内バナー用の矢印アイコン＋文言。距離帯ごとに表現を変え、単調な繰り返しを避ける。 */
internal data class NavInstruction(val arrowIcon: Int, val text: String)

// ---------------------------------------------------------------------------
// ルート指示生成
// ---------------------------------------------------------------------------

/**
 * ①基本経路指示の構築。graphLock.read 保持下・バックグラウンドスレッドで呼ぶこと。
 * 判定理由は Logcat (CycleMapInstruction) に1ルート1回だけ出す。
 */
internal fun buildRouteInstructions(
    graph: LazyMappedRoadGraph?,
    nodeIds: List<Long>,
    coordinates: List<Pair<Double, Double>>,
): List<RouteInstruction> {
    if (graph == null || nodeIds.size < 3 || coordinates.size != nodeIds.size) return emptyList()
    val points = coordinates.map { (lat, lon) -> RoutePoint(lat, lon) }
    // グラフ内インデックスは nodeIndexOf で引く (ルート内位置とは別物。混同注意)。
    fun graphIndexOf(nodeId: Long): Int? = try {
        graph.nodeIndexOf(nodeId)
    } catch (e: IllegalArgumentException) {
        null
    }
    fun branchesOf(nodeId: Long): List<TurnClassifier.MappedBranch> {
        val index = graphIndexOf(nodeId) ?: return emptyList()
        val from = graph.nodeAt(index)
        return graph.outgoingBranches(index).mapNotNull { branch ->
            val to = graph.nodeAt(branch.targetIndex)
            val bearing = bearingBetween(from.latitude, from.longitude, to.latitude, to.longitude)
                ?: return@mapNotNull null
            TurnClassifier.MappedBranch(bearing, branch.roadTypeName, branch.distanceMeters)
        }
    }
    // ルート上の edge の roadType (ランプ・側道・合流判定用)。
    val edgeTypes = (0 until nodeIds.lastIndex).map { i ->
        val fromIndex = graphIndexOf(nodeIds[i])
        val toIndex = graphIndexOf(nodeIds[i + 1])
        if (fromIndex == null || toIndex == null) {
            "unknown"
        } else {
            graph.outgoingBranches(fromIndex).firstOrNull { it.targetIndex == toIndex }?.roadTypeName
                ?: "unknown"
        }
    }
    // ルート上の edge の道路名 (graph が道路名区間を持つときだけ)。案内の「県道41号に入る」などに使う。
    val routeBranches = (0 until nodeIds.lastIndex).map { i ->
        val fromIndex = graphIndexOf(nodeIds[i])
        val toIndex = graphIndexOf(nodeIds[i + 1])
        if (fromIndex == null || toIndex == null) null
        else graph.outgoingBranches(fromIndex).firstOrNull { it.targetIndex == toIndex }
    }
    val edgeNames: List<String?> = routeBranches.map { it?.roadName }
    val instructions = TurnClassifier.classifyMappedRoute(
        nodeIds = nodeIds,
        coordinates = points,
        branchesOf = ::branchesOf,
        edgeTypes = edgeTypes,
    ).map { ins ->
        ins.copy(
            outgoingRoadName = outgoingRoadNameAt(edgeNames, ins.routeIndex),
            // 曲がり角に入ってくる辺 (routeIndex-1 → routeIndex) の車線
            incomingTurnLanes = routeBranches.getOrNull(ins.routeIndex - 1)?.turnLanes,
        )
    }
    for (instruction in instructions) {
        runCatching {
            Log.d(
                "CycleMapInstruction",
                instruction.debugLine(
                    currentEdge = nodeIds.getOrNull(instruction.routeIndex - 1)?.toString() ?: "-",
                    nextEdge = instruction.nodeId.toString(),
                    junctionType = "deg${instruction.junctionBranches}",
                    icon = IconResolver.materialIconName(instruction.type),
                ),
            )
        }
    }
    return instructions
}

/**
 * 曲がり角 (ルート内の [routeIndex] 番目のノード) から先の道路名。曲がった直後の短い区間に名前が無い
 * (交差点内の取り付け道路など) こともあるので、先の 3 区間まで見る。
 */
internal fun outgoingRoadNameAt(edgeNames: List<String?>, routeIndex: Int): String? =
    (routeIndex until (routeIndex + 3).coerceAtMost(edgeNames.size)).firstNotNullOfOrNull { edgeNames.getOrNull(it) }

// ---------------------------------------------------------------------------
// ナビ案内バナー文言生成
// ---------------------------------------------------------------------------

/**
 * 案内ヘッダー用の統一表示ステップ。
 * 矢印・距離・案内文・道路名・車線・次の予告が同じステップから決まるため食い違わない。
 */
internal data class NavGuidanceDisplay(
    val turnType: TurnType,
    val distanceMeters: Double,
    val instructionText: String,
    val targetInstruction: RouteInstruction?,
    val nextInstruction: RouteInstruction?,
    val distanceToNextMeters: Double?,
)

private fun straightGuidanceText(distanceMeters: Double): String = when (((distanceMeters / 250).toInt()) % 3) {
    0 -> "このまま直進です"
    1 -> "道なりに直進してください"
    else -> "この先しばらく直進です"
}

private fun maneuverGuidanceText(instruction: RouteInstruction, distanceMeters: Double): String {
    val label = IconResolver.getLabel(instruction.type, instruction.turnAngleDegrees)
    return when {
        distanceMeters >= 100.0 -> "${label}です"
        distanceMeters >= 30.0 -> "まもなく${label}です"
        else -> "${label}です"
    }
}

internal fun resolveNavGuidanceDisplay(
    progress: RouteProgress?,
    remainingMeters: Double?,
    richInstructions: List<RouteInstruction> = emptyList(),
    arrivalRadiusMeters: Double = ARRIVAL_RADIUS_METERS_NAV,
): NavGuidanceDisplay {
    if (progress == null) {
        return NavGuidanceDisplay(TurnType.Straight, 0.0, "直進", null, null, null)
    }
    val traveled = progress.distanceFromStartMeters
    val remaining = (remainingMeters ?: (progress.routeDistanceMeters - traveled)).coerceAtLeast(0.0)
    if (remaining <= arrivalRadiusMeters) {
        return NavGuidanceDisplay(TurnType.Arrive, remaining, "目的地に到着しました", null, null, null)
    }

    val future = richInstructions.filter { it.distanceFromStartMeters > traveled }
    val richNext = future.firstOrNull()
    if (richNext != null) {
        val richDist = (richNext.distanceFromStartMeters - traveled).coerceAtLeast(0.0)
        if (richNext.type == InstructionType.STRAIGHT) {
            val upcoming = future.firstOrNull { it.type != InstructionType.STRAIGHT }
            val upcomingDist = upcoming?.let { (it.distanceFromStartMeters - traveled).coerceAtLeast(0.0) }
            if (upcoming != null && upcomingDist != null && upcomingDist < 200.0) {
                val upcomingIdx = future.indexOf(upcoming)
                val after = future.drop(upcomingIdx + 1).firstOrNull { it.type != InstructionType.STRAIGHT }
                val distAfter = after?.let { (it.distanceFromStartMeters - upcoming.distanceFromStartMeters).coerceAtLeast(0.0) }
                return NavGuidanceDisplay(
                    turnType = upcoming.toGuidanceTurnType(),
                    distanceMeters = upcomingDist,
                    instructionText = maneuverGuidanceText(upcoming, upcomingDist),
                    targetInstruction = upcoming,
                    nextInstruction = after,
                    distanceToNextMeters = distAfter,
                )
            }
            val straightDist = upcomingDist ?: remaining
            return NavGuidanceDisplay(
                turnType = TurnType.Straight,
                distanceMeters = straightDist,
                instructionText = straightGuidanceText(richDist),
                targetInstruction = null,
                nextInstruction = upcoming,
                distanceToNextMeters = upcomingDist,
            )
        }
        if (richDist >= 500.0) {
            return NavGuidanceDisplay(
                turnType = TurnType.Straight,
                distanceMeters = richDist,
                instructionText = straightGuidanceText(richDist),
                targetInstruction = null,
                nextInstruction = richNext,
                distanceToNextMeters = richDist,
            )
        }
        val after = future.drop(1).firstOrNull { it.type != InstructionType.STRAIGHT }
        val distAfter = after?.let { (it.distanceFromStartMeters - richNext.distanceFromStartMeters).coerceAtLeast(0.0) }
        return NavGuidanceDisplay(
            turnType = richNext.toGuidanceTurnType(),
            distanceMeters = richDist,
            instructionText = maneuverGuidanceText(richNext, richDist),
            targetInstruction = richNext,
            nextInstruction = after,
            distanceToNextMeters = distAfter,
        )
    }

    val distance = (progress.distanceToNextManeuverMeters ?: remaining).coerceAtLeast(0.0)
    val type = progress.nextManeuver?.type
    if (type == null || distance >= 500.0) {
        return NavGuidanceDisplay(TurnType.Straight, distance, straightGuidanceText(distance), null, null, null)
    }
    return when (type) {
        ManeuverType.RIGHT -> NavGuidanceDisplay(
            turnType = TurnType.Right,
            distanceMeters = distance,
            instructionText = when {
                distance >= 300.0 -> "右折です"
                distance >= 100.0 -> if ((distance.toInt() / 50) % 2 == 0) "まもなく右折です" else "右方向です"
                else -> if ((distance.toInt() / 30) % 2 == 0) "まもなく右へお進みください" else "右方向です"
            },
            targetInstruction = null,
            nextInstruction = null,
            distanceToNextMeters = null,
        )
        ManeuverType.LEFT -> NavGuidanceDisplay(
            turnType = TurnType.Left,
            distanceMeters = distance,
            instructionText = when {
                distance >= 300.0 -> "左折です"
                distance >= 100.0 -> if ((distance.toInt() / 50) % 2 == 0) "まもなく左折です" else "左方向です"
                else -> if ((distance.toInt() / 30) % 2 == 0) "まもなく左へお進みください" else "左方向です"
            },
            targetInstruction = null,
            nextInstruction = null,
            distanceToNextMeters = null,
        )
        ManeuverType.U_TURN -> NavGuidanceDisplay(
            turnType = TurnType.Uturn,
            distanceMeters = distance,
            instructionText = when {
                distance >= 100.0 -> "Uターンです"
                distance >= 30.0 -> "まもなくUターンです"
                else -> "ここでUターンしてください"
            },
            targetInstruction = null,
            nextInstruction = null,
            distanceToNextMeters = null,
        )
    }
}

private const val ARRIVAL_RADIUS_METERS_NAV = 30.0

internal fun navigationInstruction(
    progress: RouteProgress?,
    remainingMeters: Double?,
    richInstructions: List<RouteInstruction> = emptyList(),
    arrivalRadiusMeters: Double = ARRIVAL_RADIUS_METERS_NAV,
): NavInstruction {
    val toNext = progress?.distanceToNextManeuverMeters
    val distance = toNext ?: remainingMeters ?: return NavInstruction(R.drawable.ic_ms_straight, "このまま直進です")
    if (remainingMeters != null && remainingMeters <= arrivalRadiusMeters) {
        return NavInstruction(R.drawable.ic_lucide_flag, "目的地に到着しました")
    }
    // ①基本経路指示があれば優先する。無ければ従来の Maneuver 表示にフォールバック。
    val traveled = progress?.distanceFromStartMeters ?: 0.0
    val richNext = richInstructions.firstOrNull { it.distanceFromStartMeters > traveled }
    if (richNext != null) {
        val richDistance = (richNext.distanceFromStartMeters - traveled).coerceAtLeast(0.0)
        // BUG-39 fix: STRAIGHTの場合、直後にある非STRAIGHT指示を先読みし事前告知する。
        // 30m先STRAIGHT＋60m先右折のようなケースで右折の告知が隠れるのを防ぐ。
        if (richNext.type == InstructionType.STRAIGHT) {
            val upcoming = richInstructions.firstOrNull {
                it.distanceFromStartMeters > traveled && it.type != InstructionType.STRAIGHT
            }
            if (upcoming != null) {
                val upcomingDist = (upcoming.distanceFromStartMeters - traveled).coerceAtLeast(0.0)
                if (upcomingDist < 200.0) {
                    val label = IconResolver.getLabel(upcoming.type, upcoming.turnAngleDegrees)
                    val text = when {
                        upcomingDist >= 100.0 -> "${upcomingDist.roundToInt()}m先${label}です"
                        upcomingDist >= 30.0 -> "まもなく${label}です"
                        else -> "${label}です"
                    }
                    return NavInstruction(IconResolver.resolveIconResId(upcoming.type, upcoming.turnAngleDegrees), text)
                }
            }
            val text = when (((richDistance / 250).toInt()) % 3) {
                0 -> "このまま直進です"
                1 -> "道なりに直進してください"
                else -> "この先しばらく直進です"
            }
            return NavInstruction(IconResolver.resolveIconResId(InstructionType.STRAIGHT), text)
        }
        if (richDistance >= 500.0) {
            val text = when (((richDistance / 250).toInt()) % 3) {
                0 -> "このまま直進です"
                1 -> "道なりに直進してください"
                else -> "この先しばらく直進です"
            }
            return NavInstruction(IconResolver.resolveIconResId(InstructionType.STRAIGHT), text)
        }
        val label = IconResolver.getLabel(richNext.type, richNext.turnAngleDegrees)
        val text = when {
            richDistance >= 100.0 -> "${richDistance.roundToInt()}m先${label}です"
            richDistance >= 30.0 -> "まもなく${label}です"
            else -> "${label}です"
        }
        return NavInstruction(IconResolver.resolveIconResId(richNext.type, richNext.turnAngleDegrees), text)
    }
    val type = progress?.nextManeuver?.type
    if (type == null || distance >= 500.0) {
        val text = when (((distance / 250).toInt()) % 3) {
            0 -> "このまま直進です"
            1 -> "道なりに直進してください"
            else -> "この先しばらく直進です"
        }
        return NavInstruction(R.drawable.ic_ms_straight, text)
    }
    return when (type) {
        com.gorite.cyclemap.routing.ManeuverType.RIGHT -> NavInstruction(
            R.drawable.ic_ms_turn_right,
            when {
                distance >= 300.0 -> "${distance.roundToInt()}m先を右折です"
                distance >= 100.0 ->
                    if ((distance.toInt() / 50) % 2 == 0) "まもなく右折です" else "${distance.roundToInt()}m先、右方向です"
                else ->
                    if ((distance.toInt() / 30) % 2 == 0) "まもなく右へお進みください" else "右方向です"
            },
        )
        com.gorite.cyclemap.routing.ManeuverType.LEFT -> NavInstruction(
            R.drawable.ic_ms_turn_left,
            when {
                distance >= 300.0 -> "${distance.roundToInt()}m先を左折です"
                distance >= 100.0 ->
                    if ((distance.toInt() / 50) % 2 == 0) "まもなく左折です" else "${distance.roundToInt()}m先、左方向です"
                else ->
                    if ((distance.toInt() / 30) % 2 == 0) "まもなく左へお進みください" else "左方向です"
            },
        )
        com.gorite.cyclemap.routing.ManeuverType.U_TURN -> NavInstruction(
            R.drawable.ic_ms_u_turn_left,
            when {
                distance >= 100.0 -> "${distance.roundToInt()}m先でUターンです"
                distance >= 30.0 -> "まもなくUターンです"
                else -> "ここでUターンしてください"
            },
        )
    }
}

// ---------------------------------------------------------------------------
// サービス位置情報更新 Composable
// ---------------------------------------------------------------------------

@Composable
internal fun ServiceLocationUpdates(
    context: Context,
    onLocationChanged: (location: Location, recording: Boolean, recordedPoints: Int) -> Unit,
    onSpeedChanged: (Double) -> Unit,
    onGpsStatusChanged: (GpsSignalStatus) -> Unit = {},
) {
    val onLocation by rememberUpdatedState(onLocationChanged)
    val onSpeed by rememberUpdatedState(onSpeedChanged)
    val onStatus by rememberUpdatedState(onGpsStatusChanged)
    DisposableEffect(context) {
        var serviceRef: LocationTrackingService? = null
        val locationListener: (Location) -> Unit = listener@{ newLocation ->
            // 検証用の模擬位置を流している間は、本物の GPS を無視する (debug ビルドの adb 検証のみ)
            if (com.gorite.cyclemap.verify.SimulatedLocationBus.isActive()) return@listener
            onSpeed(newLocation.speed.toDouble())
            val service = serviceRef
            onLocation(newLocation, service?.recording == true, service?.recordedPoints ?: 0)
        }
        val statusListener: (GpsSignalStatus) -> Unit = { status ->
            onStatus(status)
        }
        val connection = locationServiceConnection { service ->
            serviceRef = service
            service.addListener(locationListener)
            service.addStatusListener(statusListener)
        }
        LocationTrackingService.bind(context, connection)
        // 検証用の模擬位置 (debug ビルドの LocationDebugReceiver からだけ来る)
        val simJob = kotlinx.coroutines.MainScope().launch {
            com.gorite.cyclemap.verify.SimulatedLocationBus.locations.collect { sim ->
                onSpeed(sim.speed.toDouble())
                onLocation(sim, serviceRef?.recording == true, serviceRef?.recordedPoints ?: 0)
            }
        }
        onDispose {
            simJob.cancel()
            serviceRef?.removeListener(locationListener)
            serviceRef?.removeStatusListener(statusListener)
            runCatching { context.unbindService(connection) }
        }
    }
}

// ---------------------------------------------------------------------------
// コンパス更新 Composable
// ---------------------------------------------------------------------------

@Composable
internal fun CompassUpdates(
    context: Context,
    enabled: Boolean = true,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onHeadingChanged: (Float) -> Unit,
) {
    val currentOnHeadingChanged by rememberUpdatedState(onHeadingChanged)
    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose {}
        }
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        var smoothedHeading = 0f
        var initialized = false
        var isRegistered = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rotationMatrix = FloatArray(9)
                val remappedMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val wm = context.getSystemService(android.view.WindowManager::class.java)
                @Suppress("DEPRECATION")
                val displayRotation = wm?.defaultDisplay?.rotation ?: android.view.Surface.ROTATION_0
                val (axisX, axisY) = when (displayRotation) {
                    android.view.Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    android.view.Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    android.view.Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, orientation)
                val rawHeading = ((Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f)
                if (!rawHeading.isFinite() || rawHeading.isNaN()) return

                if (!initialized) {
                    smoothedHeading = rawHeading
                    initialized = true
                    currentOnHeadingChanged(smoothedHeading)
                    return
                }

                val delta = ((rawHeading - smoothedHeading + 540f) % 360f) - 180f
                val absDelta = kotlin.math.abs(delta)
                // デッドバンド: 0.6度未満の微小ジッターは破棄して無駄な地図再描画とCPU負荷を抑止
                if (absDelta < 0.6f) return

                // 適応型スムージング: 大きく向きを変えたときは素早く追従 (0.65f)、微小変動時は滑らか (0.35f)
                val alpha = when {
                    absDelta > 25f -> 0.65f
                    absDelta > 8f -> 0.48f
                    else -> 0.35f
                }
                smoothedHeading = (smoothedHeading + delta * alpha + 360f) % 360f
                currentOnHeadingChanged(smoothedHeading)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        fun register() {
            if (!isRegistered && rotationSensor != null) {
                sensorManager?.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                isRegistered = true
            }
        }

        fun unregister() {
            if (isRegistered) {
                sensorManager?.unregisterListener(listener)
                isRegistered = false
            }
        }

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
}

// ---------------------------------------------------------------------------
// 地図回転のスムーズ補間アニメーター (VSync同期)
// ---------------------------------------------------------------------------

/**
 * MapView の回転 (mapOrientation) を滑らかに追従させるコントローラー。
 *
 * センサーは約16〜60msごとに目標の向きを更新する。そのたびにアニメーションを作り直すと
 * 「止まっては動く」途切れが出るため、目標だけを更新し、画面の描画フレーム (Choreographer) ごとに
 * 現在の向きを目標へ指数的に近づける。目標に着いたらフレーム購読を止める (バッテリー保護)。
 */
internal class MapOrientationAnimator(
    private val getMapView: () -> MapView?,
    private val getVectorMap: () -> org.maplibre.android.maps.MapLibreMap? = { null },
    private val isVectorActive: () -> Boolean = { false },
    private val onOrientationUpdated: () -> Unit,
) {
    private val follower = com.gorite.cyclemap.ui.mapview.AngleFollower(timeConstantSec = 0.12f)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var targetHeading: Float = Float.NaN
    private var running = false
    private var lastFrameNanos = 0L

    /** cancel() を別スレッドから呼んだ後に開始された回転を、遅れて届いた stop() が止めないようにする世代番号。 */
    @Volatile private var generation = 0
    private val frameCallback = android.view.Choreographer.FrameCallback { nanos -> onFrame(nanos) }

    /** 指で回している最中は、センサー側の回転を止めて操作を優先する。 */
    private fun userGestureInProgress(): Boolean {
        val vectorView = getVectorMap() ?: return false
        if (!isVectorActive()) return false
        val gestures = vectorView.gesturesManager ?: return false
        return gestures.shoveGestureDetector.isInProgress ||
            gestures.rotateGestureDetector.isInProgress ||
            gestures.standardScaleGestureDetector.isInProgress ||
            gestures.moveGestureDetector.isInProgress
    }

    /** 地図がいま実際に向いている方位 (度)。取得できなければ null。 */
    private fun actualHeading(): Float? {
        val view = getMapView()
        val vectorView = getVectorMap()
        return when {
            isVectorActive() && vectorView != null -> vectorView.cameraPosition.bearing.toFloat()
            view != null -> -view.mapOrientation
            vectorView != null -> vectorView.cameraPosition.bearing.toFloat()
            else -> null
        }
    }

    fun rotateTo(newHeading: Float, animated: Boolean = true) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { rotateTo(newHeading, animated) }
            return
        }
        if (getMapView() == null && getVectorMap() == null) return
        if (userGestureInProgress()) {
            stop()
            return
        }
        val target = com.gorite.cyclemap.ui.mapview.Angles.normalize(newHeading)
        if (!animated) {
            stop()
            follower.snap(target)
            applyHeading(target)
            return
        }
        targetHeading = target
        if (running) return
        // 止まっている間にユーザーが地図を回したかもしれないので、実際の向きから再開する
        actualHeading()?.let { follower.snap(it) }
        if (follower.isSettled(target) || kotlin.math.abs(com.gorite.cyclemap.ui.mapview.Angles.shortestDelta(follower.current, target)) < 0.3f) return
        running = true
        generation++
        lastFrameNanos = 0L
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(nanos: Long) {
        if (!running) return
        if (userGestureInProgress()) {
            stop()
            return
        }
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((nanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        lastFrameNanos = nanos
        applyHeading(follower.step(targetHeading, dt))
        if (follower.isSettled(targetHeading)) {
            stop()
        } else {
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun applyHeading(heading: Float) {
        val norm = com.gorite.cyclemap.ui.mapview.Angles.normalize(heading)
        getMapView()?.setMapOrientation(-norm)
        getVectorMap()?.let { vMap ->
            vMap.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder(vMap.cameraPosition)
                .bearing(norm.toDouble())
                .build()
        }
        onOrientationUpdated()
    }

    private fun stop() {
        if (running) android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
        running = false
    }

    fun reset(animated: Boolean = true) {
        rotateTo(0f, animated)
    }

    fun cancel() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            stop()
        } else {
            val requested = generation
            mainHandler.post { if (generation == requested) stop() }
        }
    }
}
