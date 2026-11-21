package com.gorite.cyclemap.ui.cycling

import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.tracking.RideSnapshot
import com.gorite.cyclemap.tracking.RideState
import com.gorite.cyclemap.ui.theme.CycleMapTheme

/**
 * 地図を表示せず、現在速度・距離・時間・目的地方向を一瞥で読めるサイコン画面。
 * 状態 (タイマーや位置購読) や画面常時点灯の制御は持たず、引数から純粋に描画する。
 */
@Composable
fun CycleComputerScreen(
    snapshot: RideSnapshot,
    destination: CycleComputerDestination?,   // null = 目的地なし
    clockText: String,                        // 例 "14:32" (呼び出し側が渡す)
    onStartPause: () -> Unit,                 // 開始 / 一時停止 / 再開 の切り替え
    onResetLongPress: () -> Unit,             // 長押しでリセット (誤操作防止)
    onExitMode: () -> Unit,                   // 長押しで通常モードへ戻る
    modifier: Modifier = Modifier,
    roadRuleContent: (@Composable () -> Unit)? = null,
    isGpxRecording: Boolean = false,
    onFinishAndSave: () -> Unit = onResetLongPress,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (isLandscape) {
            CycleComputerLandscapeContent(
                snapshot = snapshot,
                roadRuleContent = roadRuleContent,
                destination = destination,
                clockText = clockText,
                isGpxRecording = isGpxRecording,
                onStartPause = onStartPause,
                onFinishAndSave = onFinishAndSave,
                onResetLongPress = onResetLongPress,
                onExitMode = onExitMode,
            )
        } else {
            CycleComputerPortraitContent(
                snapshot = snapshot,
                roadRuleContent = roadRuleContent,
                destination = destination,
                clockText = clockText,
                isGpxRecording = isGpxRecording,
                onStartPause = onStartPause,
                onFinishAndSave = onFinishAndSave,
                onResetLongPress = onResetLongPress,
                onExitMode = onExitMode,
            )
        }
    }
}

/** Height is the usable area after system insets; scrolling handles the remaining overflow. */
internal fun cycleComputerSpeedFontSize(heightDp: Float, fontScale: Float, landscape: Boolean): Float {
    val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val height = heightDp.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return (height * (if (landscape) 0.40f else 0.22f) / scale).coerceIn(36f, 124f)
}

@Composable
private fun CycleComputerPortraitContent(
    snapshot: RideSnapshot,
    roadRuleContent: (@Composable () -> Unit)?,
    destination: CycleComputerDestination?,
    clockText: String,
    isGpxRecording: Boolean,
    onStartPause: () -> Unit,
    onFinishAndSave: () -> Unit,
    onResetLongPress: () -> Unit,
    onExitMode: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val speedSize = cycleComputerSpeedFontSize(maxHeight.value, LocalDensity.current.fontScale, false).sp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Measure actions first; only the instruments may scroll out of view.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CycleComputerStatusBar(
                    clockText = clockText,
                    state = snapshot.state,
                    isGpxRecording = isGpxRecording,
                )
                roadRuleContent?.invoke()
                SpeedHeroDisplay(snapshot.speedKmh, snapshot.state == RideState.PAUSED, speedSize)
                DestinationBand(destination = destination, bandHeight = 66.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                StatsGrid(snapshot = snapshot, compact = false)
            }
            CycleComputerActionBar(
                state = snapshot.state,
                isLandscape = false,
                isGpxRecording = isGpxRecording,
                onStartPause = onStartPause,
                onFinishAndSave = onFinishAndSave,
                onResetLongPress = onResetLongPress,
                onExitMode = onExitMode,
            )
        }
    }
}

@Composable
private fun CycleComputerLandscapeContent(
    snapshot: RideSnapshot,
    roadRuleContent: (@Composable () -> Unit)?,
    destination: CycleComputerDestination?,
    clockText: String,
    isGpxRecording: Boolean,
    onStartPause: () -> Unit,
    onFinishAndSave: () -> Unit,
    onResetLongPress: () -> Unit,
    onExitMode: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val speedSize = cycleComputerSpeedFontSize(maxHeight.value, LocalDensity.current.fontScale, true).sp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CycleComputerStatusBar(
                        clockText = clockText,
                        state = snapshot.state,
                        isGpxRecording = isGpxRecording,
                    )
                    roadRuleContent?.invoke()
                    SpeedHeroDisplay(snapshot.speedKmh, snapshot.state == RideState.PAUSED, speedSize)
                    DestinationBand(destination = destination, bandHeight = 64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
                }
                Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    StatsGrid(snapshot = snapshot, compact = true)
                }
            }
            CycleComputerActionBar(
                state = snapshot.state,
                isLandscape = true,
                isGpxRecording = isGpxRecording,
                onStartPause = onStartPause,
                onFinishAndSave = onFinishAndSave,
                onResetLongPress = onResetLongPress,
                onExitMode = onExitMode,
            )
        }
    }
}

private val previewRunningSnapshot = RideSnapshot(
    state = RideState.RUNNING,
    speedKmh = 26.8,
    distanceM = 18_420.0,
    movingTimeS = 2_745.0,
    elapsedS = 2_910.0,
    averageKmh = 24.1,
    maxKmh = 39.4,
    elevationGainM = 245.0,
)

private val previewPausedSnapshot = previewRunningSnapshot.copy(
    state = RideState.PAUSED,
    speedKmh = 0.0,
)

@Preview(name = "縦・走行中・直線目的地 (墨と朱)", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewPortraitRunningStraightDest() {
    CycleMapTheme(darkTheme = true) {
        CycleComputerScreen(
            snapshot = previewRunningSnapshot,
            destination = CycleComputerDestination(
                remainingMeters = 12_300.0,
                isStraightLine = true,
                relativeBearingDegrees = 35.0,
            ),
            clockText = "14:32",
            onStartPause = {},
            onResetLongPress = {},
            onExitMode = {},
        )
    }
}

@Preview(name = "縦・一時停止・ルート案内", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewPortraitPausedRouteDest() {
    CycleMapTheme(darkTheme = true) {
        CycleComputerScreen(
            snapshot = previewPausedSnapshot,
            destination = CycleComputerDestination(
                remainingMeters = 4_500.0,
                isStraightLine = false,
                nextTurnText = "右折です",
                nextTurnDistanceM = 180.0,
            ),
            clockText = "15:10",
            isGpxRecording = true,
            onStartPause = {},
            onResetLongPress = {},
            onExitMode = {},
        )
    }
}

@Preview(name = "縦・待機・目的地なし (ライト)", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun PreviewPortraitIdleNoDestLight() {
    CycleMapTheme(darkTheme = false) {
        CycleComputerScreen(
            snapshot = RideSnapshot(),
            destination = null,
            clockText = "09:05",
            onStartPause = {},
            onResetLongPress = {},
            onExitMode = {},
        )
    }
}

@Preview(name = "横・走行中・ルート案内", showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun PreviewLandscapeRunningRouteDest() {
    CycleMapTheme(darkTheme = true) {
        CycleComputerScreen(
            snapshot = previewRunningSnapshot,
            destination = CycleComputerDestination(
                remainingMeters = 4_500.0,
                isStraightLine = false,
                nextTurnText = "右折です",
                nextTurnDistanceM = 180.0,
            ),
            clockText = "14:32",
            onStartPause = {},
            onResetLongPress = {},
            onExitMode = {},
        )
    }
}

@Preview(name = "横・一時停止・目的地なし", showBackground = true, widthDp = 800, heightDp = 360)
@Composable
private fun PreviewLandscapePausedNoDest() {
    CycleMapTheme(darkTheme = true) {
        CycleComputerScreen(
            snapshot = previewPausedSnapshot,
            destination = null,
            clockText = "15:10",
            onStartPause = {},
            onResetLongPress = {},
            onExitMode = {},
        )
    }
}

@Preview(name = "Small portrait, 200% text", showBackground = true, widthDp = 320, heightDp = 480, fontScale = 2f)
@Preview(name = "Small landscape, 200% text", showBackground = true, widthDp = 640, heightDp = 320, fontScale = 2f)
@Composable
private fun PreviewSmallScreenLargeText() {
    PreviewSmallScreenLargeTextContent(isGpxRecording = true)
}

@Preview(name = "Small portrait, 200% text, no GPX", showBackground = true, widthDp = 320, heightDp = 480, fontScale = 2f)
@Preview(name = "Small landscape, 200% text, no GPX", showBackground = true, widthDp = 640, heightDp = 320, fontScale = 2f)
@Composable
private fun PreviewSmallScreenLargeTextWithoutGpx() {
    PreviewSmallScreenLargeTextContent(isGpxRecording = false)
}

@Composable
private fun PreviewSmallScreenLargeTextContent(isGpxRecording: Boolean) {
    CycleMapTheme {
        CycleComputerScreen(
            snapshot = previewRunningSnapshot,
            destination = null,
            clockText = "14:32",
            isGpxRecording = isGpxRecording,
            roadRuleContent = { androidx.compose.material3.Text("Road rule warning") },
            onStartPause = {},
            onResetLongPress = {},
            onExitMode = {},
        )
    }
}
