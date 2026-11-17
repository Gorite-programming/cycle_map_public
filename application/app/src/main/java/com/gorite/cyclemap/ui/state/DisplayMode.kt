package com.gorite.cyclemap.ui.state

import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf

/** 画面の表示モード。通常 (地図とナビ) / サイコン (速度・距離・時間だけ) / シンプル (地図だけ・未実装)。 */
enum class DisplayMode(val label: String) {
    NORMAL("通常"),
    CYCLE_COMPUTER("サイコン"),
    SIMPLE("シンプル"),
}

/** 設定などの深い所から表示モードを読み書きするための入口 (LocalLayerSelector と同じ流儀)。 */
class DisplayModeController(
    val mode: State<DisplayMode>,
    val set: (DisplayMode) -> Unit,
)

val LocalDisplayModeController = compositionLocalOf<DisplayModeController?> { null }

internal const val PREF_DISPLAY_MODE = "display_mode"

/** 保存された値から復元する。壊れた値は通常に戻す。 */
fun displayModeFromPref(saved: String?): DisplayMode =
    DisplayMode.entries.firstOrNull { it.name == saved } ?: DisplayMode.NORMAL
