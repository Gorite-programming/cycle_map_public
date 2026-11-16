package com.gorite.cyclemap.ui.cycling

/**
 * サイコン画面の「目的地」欄。目的地が無ければ null を渡す。
 * ルートがあるとき: [remainingMeters] は道なり、[isStraightLine] = false、[nextTurnText]/[nextTurnDistanceM] に次の曲がり角。
 * ルートが無いとき: [remainingMeters] は直線距離、[isStraightLine] = true、[relativeBearingDegrees] は進行方向に対する目的地の向き
 *   (0 = 真正面、90 = 右、-90 = 左、±180 = 真後ろ。進行方向が不明なら null)。
 */
data class CycleComputerDestination(
    val remainingMeters: Double,
    val isStraightLine: Boolean,
    val relativeBearingDegrees: Double? = null,
    val nextTurnText: String? = null,
    val nextTurnDistanceM: Double? = null,
)
