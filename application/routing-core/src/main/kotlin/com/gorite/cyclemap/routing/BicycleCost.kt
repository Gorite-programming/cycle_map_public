package com.gorite.cyclemap.routing

import kotlin.math.abs

/**
 * 自転車の経路コストの係数 (道路の種類と勾配)。
 *
 * [CyclingCostModel] (メモリ上のグラフ) と [LazyMappedRoadGraph] (mmap のグラフ) の両方がここを参照する。
 * 以前は同じ値を別々に書いていて、片方だけ変えると A* のヒューリスティック (直線距離 × [MIN_MULTIPLIER]) が
 * 実際のコストを上回り、経路が最短でなくなるおそれがあった。値を変えるときはここだけを変える。
 *
 * 値を変えると経路が変わるので、CH (`<name>.graph.ch`) を作り直すこと (グラフの指紋は係数を含まないため自動では気づかない)。
 */
object BicycleCost {
    /**
     * 1 m あたりのコストの下限 (全モード共通)。A* のヒューリスティックはこれ × 直線距離。
     * すべての合成係数をこの値以上に切り上げるので、ヒューリスティックが実コストを上回らない。
     */
    const val MIN_MULTIPLIER = 0.90

    const val PRIMARY = 0.90
    const val SECONDARY = 0.95
    const val TERTIARY = 1.00
    const val RESIDENTIAL = 1.20
    const val UNCLASSIFIED = 1.25
    const val SERVICE = 1.35
    const val TRACK = 1.60
    const val PATH = 1.80

    /** 上のどれにも当たらない道路の種類。 */
    const val OTHER = 1.15

    /** OSM の highway の値から係数を返す。自転車で通れない道 (motorway) は null。 */
    fun roadMultiplier(roadType: String): Double? = when (roadType) {
        "motorway", "motorway_link" -> null
        "primary" -> PRIMARY
        "secondary" -> SECONDARY
        "tertiary" -> TERTIARY
        "residential" -> RESIDENTIAL
        "unclassified" -> UNCLASSIFIED
        "service" -> SERVICE
        "track" -> TRACK
        "path" -> PATH
        else -> OTHER
    }

    /** 「おすすめ」の勾配の係数。grade は % (上りが正)。 */
    fun recommendedGradePenalty(grade: Double): Double = when {
        grade >= 0.0 -> 1.0 + (grade * 0.02) + (grade * grade * 0.002) // 緩やかな上りは+2%、急勾配は二次関数的に増加
        grade >= -4.0 -> (1.0 + grade * 0.02).coerceAtLeast(0.95) // 緩やかな下りは軽快（最大-5%）
        else -> 0.95 + abs(grade + 4.0) * 0.03 // -4%を超える急坂下りは減速ペナルティ（-4%で0.95から滑らかに接続）
    }
}
