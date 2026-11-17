package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.contract.RouteOption
import kotlin.math.roundToInt

/**
 * ルート見出し・候補カード・距離表示で共有する距離の整形 (四捨五入で統一)。
 * 1 km 未満は 1 m 単位 (丸めて 1000 m 以上は 1.0km)、1 km 以上は小数第 1 位まで表示する。
 */
internal fun formatRouteDistance(meters: Double): String {
    if (!meters.isFinite()) return "--"
    val m = meters.coerceAtLeast(0.0)
    if (m < 1000.0) {
        val roundedMeters = m.roundToInt()
        return if (roundedMeters >= 1000) "1.0km" else "${roundedMeters}m"
    }
    val tenths = (m / 100.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}km"
}

/** 経路プレビューに並べる、経路候補 1 本分の見出しと要約。 */
data class RouteChoice(
    val label: String,
    val detail: String,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val featureTag: String? = null,
    val elevationDiffText: String? = null,
) {
    /** 平均速度が未取得・無効なら 15km/h として候補の所要時間を求める。 */
    fun estimatedDurationSeconds(averageKmh: Double?): Double {
        val speed = averageKmh?.takeIf { it.isFinite() && it > 0.0 } ?: 15.0
        return distanceMeters / 1000.0 / speed * 3600.0
    }
}

/** 候補の一覧を、画面に並べる見出し・特徴・要約 (距離・獲得標高・登りの差) にする。 */
fun routeChoicesOf(options: List<RouteOption>): List<RouteChoice> {
    val hasAllElevation = options.size >= 2 &&
        options.all { it.elevationGainMeters.isFinite() && it.elevationGainMeters >= 0.0 } &&
        options.maxOf { it.elevationGainMeters } > 0.5
    val minDist = options.minOfOrNull { it.distanceMeters } ?: 0.0
    val minGain = if (hasAllElevation) options.minOf { it.elevationGainMeters } else 0.0

    return options.mapIndexed { i, o ->
        val distText = formatRouteDistance(o.distanceMeters)
        val gainText = if (o.elevationGainMeters.isFinite() && o.elevationGainMeters > 0.5) {
            " ・ +${o.elevationGainMeters.roundToInt()}m"
        } else {
            ""
        }
        val diffText = if (hasAllElevation) {
            val otherMin = options.filterIndexed { idx, _ -> idx != i }.minOf { it.elevationGainMeters }
            val diff = (o.elevationGainMeters - otherMin).roundToInt()
            when {
                diff <= -5 -> "登り ${diff}m"
                diff >= 5 -> "登り +${diff}m"
                else -> null
            }
        } else {
            null
        }
        val featureTag = if (options.size >= 2) {
            val isLeastClimb = hasAllElevation &&
                o.elevationGainMeters <= minGain + 1.0 &&
                options.any { it.elevationGainMeters > o.elevationGainMeters + 5.0 }
            val isShortest = o.distanceMeters <= minDist + 10.0 &&
                options.any { it.distanceMeters > o.distanceMeters + 50.0 }
            val avoidsArterial = o.id == "route_alternative" ||
                o.title.contains("裏道") ||
                o.title.contains("生活道路") ||
                o.title.contains("一般道路")
            when {
                isLeastClimb && !isShortest -> "坂が少ない"
                isShortest -> "最短"
                avoidsArterial -> "幹線を避ける"
                o.isPrimary -> "推奨"
                else -> null
            }
        } else {
            null
        }
        RouteChoice(
            label = "ルート${i + 1}",
            detail = distText + gainText,
            distanceMeters = o.distanceMeters,
            elevationGainMeters = o.elevationGainMeters,
            featureTag = featureTag,
            elevationDiffText = diffText,
        )
    }
}

/** 地図に灰色で出す代替ルートの線。選んでいる候補以外の全て。範囲外の番号なら全部が代替になる。 */
fun alternativeLinesOf(options: List<RouteOption>, selectedIndex: Int): List<List<Pair<Double, Double>>> =
    options.filterIndexed { i, _ -> i != selectedIndex }.map { it.points }.filter { it.size >= 2 }
