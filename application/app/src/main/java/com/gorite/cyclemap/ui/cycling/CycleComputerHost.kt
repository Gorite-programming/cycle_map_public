package com.gorite.cyclemap.ui.cycling

import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.tracking.RideSession
import com.gorite.cyclemap.tracking.RideState
import com.gorite.cyclemap.ui.effects.KeepScreenOnEffect
import com.gorite.cyclemap.ui.effects.shouldKeepScreenOnInCycleComputer
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * サイコン画面を、状態 (RideSession・位置・ルート進捗) とつなぐ入れ物。地図の上に全面で重ねて出す。
 * 1 秒ごとに値を読み直し、表示している間は画面を点けたままにする。
 */
@Composable
fun CycleComputerHost(
    rideSession: RideSession,
    store: com.gorite.cyclemap.tracking.RideSessionStore,
    location: Location?,
    destLat: Double?,
    destLon: Double?,
    progress: RouteProgress?,
    onExit: () -> Unit,
    onStartRecording: () -> Unit = {},
    onPauseRecording: () -> Unit = {},
    onFinishRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    // 走行中の値を 15 秒ごとに保存する (アプリが落ちても戻せるように)。画面を離れるときにも保存する。
    LaunchedEffect(rideSession) {
        while (true) {
            delay(15_000)
            if (rideSession.snapshot(System.currentTimeMillis()).state != RideState.IDLE) store.save(rideSession)
        }
    }
    DisposableEffect(rideSession) {
        onDispose {
            if (rideSession.snapshot(System.currentTimeMillis()).state != RideState.IDLE) store.save(rideSession)
        }
    }
    val snapshot = rideSession.snapshot(nowMs)
    // 一時停止が 60 秒続いたら画面常時点灯を解除し、通常の消灯に任せる。
    var pausedSinceMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(snapshot.state) {
        pausedSinceMs = if (snapshot.state == RideState.PAUSED) System.currentTimeMillis() else null
    }
    KeepScreenOnEffect(
        enabled = shouldKeepScreenOnInCycleComputer(
            state = snapshot.state,
            pausedSinceMs = pausedSinceMs,
            nowMs = nowMs,
        ),
    )
    BackHandler(onBack = onExit)

    val clockFormat = remember { SimpleDateFormat("H:mm", Locale.JAPAN) }
    val destination = buildCycleComputerDestination(
        destLat = destLat,
        destLon = destLon,
        lat = location?.latitude,
        lon = location?.longitude,
        courseDegrees = location?.takeIf { it.hasBearing() && it.hasSpeed() && it.speed >= 1f }?.bearing?.toDouble(),
        progress = progress,
    )
    val ruleKind = com.gorite.cyclemap.ui.state.LocalRoadRuleController.current?.kind?.value
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CycleComputerScreen(
            snapshot = snapshot,
            // 警告が無いときは null にする (画面側が、警告の分だけ縦の余白を空ける判断に使う)
            roadRuleContent = ruleKind?.let { kind ->
                {
                    RoadRuleBadge(
                        kind = kind,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = roadRuleText(kind)
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
            },
            destination = destination,
            clockText = clockFormat.format(Date(nowMs)),
            onStartPause = {
                if (rideSession.snapshot(System.currentTimeMillis()).state == RideState.RUNNING) {
                    rideSession.pause(System.currentTimeMillis())
                    onPauseRecording()
                } else {
                    rideSession.start(System.currentTimeMillis())
                    onStartRecording()
                }
                store.save(rideSession)
            },
            onResetLongPress = { onFinishRecording(); rideSession.reset(); store.clear() },
            onExitMode = onExit,
        )
    }
}
