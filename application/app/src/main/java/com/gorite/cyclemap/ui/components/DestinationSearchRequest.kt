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
