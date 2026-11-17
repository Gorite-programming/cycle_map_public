package com.gorite.cyclemap.ui.cycling

import android.location.Location
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 道路のルールの通知 (自動車専用・逆走など)。サイコン画面でも出す
        com.gorite.cyclemap.ui.state.LocalRoadRuleController.current?.kind?.value?.let { ruleKind ->
            RoadRuleBadge(
                kind = ruleKind,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 98.dp, start = 16.dp, end = 16.dp),
            )
        }
        CycleComputerScreen(
            snapshot = snapshot,
            destination = destination,
            clockText = clockFormat.format(Date(nowMs)),
            onStartPause = {
                if (rideSession.snapshot(System.currentTimeMillis()).state == RideState.RUNNING) {
                    rideSession.pause()
                    onPauseRecording()
                } else {
                    rideSession.start()
                    onStartRecording()
                }
                store.save(rideSession)
            },
            onResetLongPress = { onFinishRecording(); rideSession.reset(); store.clear() },
            onExitMode = onExit,
        )
    }
}
