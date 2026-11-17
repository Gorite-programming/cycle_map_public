package com.gorite.cyclemap.ui.state

import androidx.compose.runtime.Composable

/**
 * シンプルモード (地図と操作ボタンだけ) での、各部品を出すかどうか。
 * ナビ中・ルート確認中・到着の表示は、安全のため通常と同じに残す。
 */
data class SimpleModeVisibility(
    /** 上部の検索欄・メニュー・カテゴリのチップ。 */
    val topBar: Boolean,
    /** 待ち受けの盤面 (どこへ行く?、保存済み、ルートなしで走る)。 */
    val homePanel: Boolean,
    /** 下部の地名・天気のシートとタブバー。 */
    val bottomSheetAndTabs: Boolean,
    /** 右側のエリアのダウンロードボタン。 */
    val areaDownloadButton: Boolean,
    /** 施設のピン (駅・コンビニなど)。設定の値は変えず、表示だけ隠す。 */
    val poiPins: Boolean,
    /** 左上のモード切替ボタン。 */
    val modeSwitchButton: Boolean,
) {
    companion object {
        fun of(mode: DisplayMode, isNavigationActive: Boolean): SimpleModeVisibility {
            val simple = mode == DisplayMode.SIMPLE
            return SimpleModeVisibility(
                topBar = !simple,
                homePanel = !simple,
                bottomSheetAndTabs = !simple,
                areaDownloadButton = !simple,
                poiPins = !simple,
                modeSwitchButton = simple && !isNavigationActive,
            )
        }
    }
}

/** 今の表示モードがシンプルか。コントローラが無い (プレビューなど) ときは通常として扱う。 */
@Composable
fun isSimpleMode(): Boolean = LocalDisplayModeController.current?.mode?.value == DisplayMode.SIMPLE
