package com.gorite.cyclemap.routing

import kotlin.math.abs

/**
 * 自転車の経路コストの係数 (道路の種類と勾配)。
 *
 * [CyclingCostModel] (メモリ上のグラフ) と [LazyMappedRoadGraph] (mmap のグラフ) の両方がここを参照する。
 * 以前は同じ値を別々に書いていて、片方だけ変えると A* のヒューリスティック (直線距離 × [MIN_MULTIPLIER]) が
 * 実際のコストを上回り、経路が最短でなくなるおそれがあった。値を変えるときはここだけを変える。
 *
 * cycleway・trunk・接続路・生活道路・歩行者共用道も個別に評価する。
 * 係数を変えたら CH (`<name>.graph.ch`) を作り直すこと (グラフの指紋は係数を含まないため自動では気づかない)。
 * 取り込みの通行方向も変えた場合は、先に graph と索引を再生成すること。
 */
object BicycleCost {
    /**
     * 1 m あたりのコストの下限 (全モード共通)。A* のヒューリスティックはこれ × 直線距離。
     * すべての合成係数をこの値以上に切り上げるので、ヒューリスティックが実コストを上回らない。
     */
    const val MIN_MULTIPLIER = 0.90

    const val CYCLEWAY = 0.90
    const val PRIMARY = 0.90
    const val TRUNK = PRIMARY
    const val TRUNK_LINK = 0.95
    const val PRIMARY_LINK = 0.95
    const val SECONDARY_LINK = 1.00
    const val TERTIARY_LINK = 1.05
    const val SECONDARY = 0.95
    const val TERTIARY = 1.00
    const val RESIDENTIAL = 1.20
    const val UNCLASSIFIED = 1.25
    const val SERVICE = 1.35
    const val TRACK = 1.60
    const val PATH = 1.80
    const val LIVING_STREET = RESIDENTIAL
    const val FOOTWAY = TRACK
    const val PEDESTRIAN = TRACK

    /** 上のどれにも当たらない道路の種類。 */
    const val OTHER = 1.15

    /** OSM の highway の値から係数を返す。自転車で通れない道 (motorway) は null。 */
    fun roadMultiplier(roadType: String): Double? = when (roadType) {
        "motorway", "motorway_link" -> null
        "cycleway" -> CYCLEWAY
        "trunk" -> TRUNK
        "trunk_link" -> TRUNK_LINK
        "primary_link" -> PRIMARY_LINK
        "secondary_link" -> SECONDARY_LINK
        "tertiary_link" -> TERTIARY_LINK
        "living_street" -> LIVING_STREET
        "footway" -> FOOTWAY
        "pedestrian" -> PEDESTRIAN
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
