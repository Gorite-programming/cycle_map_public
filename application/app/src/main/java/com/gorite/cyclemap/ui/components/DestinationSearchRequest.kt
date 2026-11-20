package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.search.SearchCategory

/** 表示中の候補が、現在の入力・検索対象に対する結果かを判定する。 */
internal data class DestinationSearchRequest(
    val query: String,
    val category: SearchCategory,
    val origin: Pair<Double, Double>?,
    val databasePaths: List<String>,
    // 同じ入力に戻った場合も、その前の検索結果を再び有効にしない。
    private val generation: Any = Any(),
)

/**
 * 現在地を約 110 m 単位 (小数 3 桁) に丸める。走行中の測位の小さな揺れで、表示中の候補が毎回
 * 無効 (薄表示・押せない) にならないようにする。検索の起点としては十分な精度。
 */
internal fun coarseSearchOrigin(origin: Pair<Double, Double>?): Pair<Double, Double>? =
    origin?.let { Math.round(it.first * 1000.0) / 1000.0 to Math.round(it.second * 1000.0) / 1000.0 }
