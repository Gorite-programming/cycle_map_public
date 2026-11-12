package com.gorite.cyclemap.routing

/** 経路探索の結果 (LazyMappedRoadGraph・CH・県またぎの探索が返す)。 */
data class MappedRouteResult(
    val nodeIds: List<Long>,
    val totalDistanceMeters: Double,
    val totalCost: Double,
    val coordinates: List<Pair<Double, Double>> = emptyList(),
    /** 探索がキャンセル要求で中断された。到達不能とは区別すること。 */
    val wasCancelled: Boolean = false,
    /** 展開ノード上限で打ち切られた。範囲を狭めて再試行できる。 */
    val wasTruncated: Boolean = false,
    /**
     * 経路上のノードの、graph 内の番号 (nodeIds と同じ順)。LazyMappedRoadGraph の探索結果だけが持つ。
     * 座標から最寄りのノードを探し直さずに、勾配・標高を引くのに使う (実機で数秒かかっていた)。
     */
    val nodeIndices: List<Int> = emptyList(),
) {
    val isReachable: Boolean get() = nodeIds.isNotEmpty()
}

