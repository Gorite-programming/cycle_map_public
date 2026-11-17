package com.gorite.cyclemap.ui.cycling

import android.app.Activity
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.tracking.RideSession
import com.gorite.cyclemap.tracking.RideState
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
    location: Location?,
    destLat: Double?,
    destLon: Double?,
    progress: RouteProgress?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    // 表示している間だけ画面を点けたままにする。
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler(onBack = onExit)

    val snapshot = rideSession.snapshot(nowMs)
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
        CycleComputerScreen(
            snapshot = snapshot,
            destination = destination,
            clockText = clockFormat.format(Date(nowMs)),
            onStartPause = {
                if (rideSession.snapshot(System.currentTimeMillis()).state == RideState.RUNNING) rideSession.pause()
                else rideSession.start()
            },
            onResetLongPress = { rideSession.reset() },
            onExitMode = onExit,
        )
    }
}
