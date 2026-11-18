package com.gorite.cyclemap.ui.cycling

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
                onStartPause = onStartPause,
                onResetLongPress = onResetLongPress,
                onExitMode = onExitMode,
            )
        } else {
            CycleComputerPortraitContent(
                snapshot = snapshot,
                roadRuleContent = roadRuleContent,
                destination = destination,
                clockText = clockText,
                onStartPause = onStartPause,
                onResetLongPress = onResetLongPress,
                onExitMode = onExitMode,
            )
        }
    }
}

@Composable
private fun CycleComputerPortraitContent(
    snapshot: RideSnapshot,
    roadRuleContent: (@Composable () -> Unit)?,
    destination: CycleComputerDestination?,
    clockText: String,
    onStartPause: () -> Unit,
    onResetLongPress: () -> Unit,
    onExitMode: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 時計行の下に警告の実寸を確保し、大きな速度表示と重ねない。
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CycleComputerStatusBar(clockText = clockText, state = snapshot.state)
            roadRuleContent?.invoke()
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SpeedHeroDisplay(
                speedKmh = snapshot.speedKmh,
                isPaused = snapshot.state == RideState.PAUSED,
                // 警告の行 (約 44dp) を足しても、下の操作ボタンがつぶれないよう、警告が出ている間は速度の数字を小さくする
                // (S26 の実機で、124sp のままだと「リセット」「通常モードへ戻る」が高さ約 13dp につぶれた)
                speedFontSize = if (roadRuleContent != null) 76.sp else 124.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            DestinationBand(
                destination = destination,
                bandHeight = 66.dp,
            )
        }

        StatsGrid(
            snapshot = snapshot,
            compact = false,
        )

        CycleComputerActionBar(
            state = snapshot.state,
            isLandscape = false,
            onStartPause = onStartPause,
            onResetLongPress = onResetLongPress,
            onExitMode = onExitMode,
        )
    }
}

@Composable
private fun CycleComputerLandscapeContent(
    snapshot: RideSnapshot,
    roadRuleContent: (@Composable () -> Unit)?,
    destination: CycleComputerDestination?,
    clockText: String,
    onStartPause: () -> Unit,
    onResetLongPress: () -> Unit,
    onExitMode: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 左半分: 時刻・状態 + 現在速度 + 目的地
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 時計行の下に警告の実寸を確保し、大きな速度表示と重ねない。
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CycleComputerStatusBar(clockText = clockText, state = snapshot.state)
                roadRuleContent?.invoke()
            }
            SpeedHeroDisplay(
                speedKmh = snapshot.speedKmh,
                isPaused = snapshot.state == RideState.PAUSED,
                speedFontSize = 120.sp,
            )
            DestinationBand(
                destination = destination,
                bandHeight = 64.dp,
            )
        }

        // 右半分: 走行項目 + 操作ボタン
        Column(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StatsGrid(
                snapshot = snapshot,
                compact = true,
            )
            CycleComputerActionBar(
                state = snapshot.state,
                isLandscape = true,
                onStartPause = onStartPause,
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
