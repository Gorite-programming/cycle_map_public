package com.gorite.cyclemap.ui.effects

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.gorite.cyclemap.tracking.RideState

/** サイコンモードで一時停止がこの時間を超えたら、画面常時点灯を解除して通常の消灯に任せる。 */
internal const val CYCLE_COMPUTER_PAUSE_TIMEOUT_MS = 60_000L

/**
 * サイコンモード中に画面を点けたままにするかを判定する。
 * 一時停止 ([RideState.PAUSED]) が [CYCLE_COMPUTER_PAUSE_TIMEOUT_MS] 以上続いたときだけ false。
 */
internal fun shouldKeepScreenOnInCycleComputer(
    state: RideState,
    pausedSinceMs: Long?,
    nowMs: Long,
): Boolean {
    if (state != RideState.PAUSED) return true
    if (pausedSinceMs == null) return true
    return (nowMs - pausedSinceMs) < CYCLE_COMPUTER_PAUSE_TIMEOUT_MS
}

/**
 * [enabled] が true の間だけ、現在の Activity のウィンドウに [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] を付与する。
 */
@Composable
internal fun KeepScreenOnEffect(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    DisposableEffect(context) {
        // LocalContext はテーマなどの ContextWrapper のことがあるので、包みをたどって Activity を探す
        var c: android.content.Context? = context
        while (c != null && c !is Activity) c = (c as? android.content.ContextWrapper)?.baseContext
        val window = (c as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
