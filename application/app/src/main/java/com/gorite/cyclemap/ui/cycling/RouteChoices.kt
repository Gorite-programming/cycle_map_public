package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.contract.RouteOption
import java.util.Locale

/** 経路プレビューに並べる、経路候補 1 本分の見出しと要約。 */
data class RouteChoice(
    val label: String,
    val detail: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
) {
    /** 平均速度が未取得・無効なら 15km/h として候補の所要時間を求める。 */
    fun estimatedDurationSeconds(averageKmh: Double?): Double {
        val speed = averageKmh?.takeIf { it.isFinite() && it > 0.0 } ?: 15.0
        return distanceMeters / 1000.0 / speed * 3600.0
    }
}

/** 候補の一覧を、画面に並べる見出しと要約 (距離・獲得標高) にする。 */
fun routeChoicesOf(options: List<RouteOption>): List<RouteChoice> =
    options.mapIndexed { i, o ->
        val km = "%.1fkm".format(Locale.US, o.distanceMeters / 1000.0)
        val gain = if (o.elevationGainMeters > 0.5) " ・ +${o.elevationGainMeters.toInt()}m" else ""
        RouteChoice(
            label = "ルート${i + 1}",
            detail = km + gain,
            distanceMeters = o.distanceMeters,
            elevationGainMeters = o.elevationGainMeters,
        )
    }

/** 地図に灰色で出す代替ルートの線。選んでいる候補以外の全て。範囲外の番号なら全部が代替になる。 */
fun alternativeLinesOf(options: List<RouteOption>, selectedIndex: Int): List<List<Pair<Double, Double>>> =
    options.filterIndexed { i, _ -> i != selectedIndex }.map { it.points }.filter { it.size >= 2 }
