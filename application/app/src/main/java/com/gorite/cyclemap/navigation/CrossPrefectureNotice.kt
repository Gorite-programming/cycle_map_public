package com.gorite.cyclemap.navigation

/** 表示だけに使う県またぎ経路の通知判定。 */
internal data class CrossPrefectureNotice(
    val showLongCalculationNotice: Boolean,
    val showUnsupportedWarning: Boolean,
)

internal const val LONG_CROSS_PREFECTURE_CALCULATION_MESSAGE =
    "長距離の計算を行なっています。この計算には時間がかかることがあります..."

internal const val CROSS_PREFECTURE_UNSUPPORTED_MESSAGE =
    "県をまたぐ経路には未対応です。出発地の県内で目的地に最も近い地点までの経路を表示しています"

internal const val CROSS_PREFECTURE_ROUTE_MESSAGE =
    "県をまたぐ経路です。ナビ中の県の切替は、まだ試験段階です"

internal fun crossPrefectureNotice(
    startPref: String?,
    goalPref: String?,
    straightMeters: Double,
): CrossPrefectureNotice {
    val crossesPrefecture = startPref != null && goalPref != null && startPref != goalPref
    return CrossPrefectureNotice(
        showLongCalculationNotice = crossesPrefecture && straightMeters > 50_000.0,
        showUnsupportedWarning = crossesPrefecture,
    )
}

/** 停止点列に県またぎ区間があり、全体の直線距離が閾値を超えるか判定する。 */
internal fun crossPrefectureNotice(stops: List<Pair<String?, Double>>): CrossPrefectureNotice {
    val hasCrossLeg = stops.zipWithNext().any { (a, b) -> a.first != null && b.first != null && a.first != b.first }
    val totalStraightMeters = stops.zipWithNext().sumOf { (a, b) ->
        // pair's distance carries the cumulative segment length at its destination.
        b.second
    }
    return CrossPrefectureNotice(hasCrossLeg && totalStraightMeters > 50_000.0, hasCrossLeg)
}
