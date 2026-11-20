package com.gorite.cyclemap.ui.layout

internal data class AreaSelectionBounds(
    val north: Double,
    val east: Double,
    val south: Double,
    val west: Double,
)

/** 回転・傾斜した地図でも、画面上の選択矩形の四隅をすべて含める。 */
internal fun projectAreaSelectionBounds(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    project: (Float, Float) -> Pair<Double, Double>?,
): AreaSelectionBounds? {
    val corners = listOf(left to top, right to top, right to bottom, left to bottom)
    val projected = corners.map { (x, y) ->
        // 傾斜時に空などを選び、逆投影できなかった場合は選択を確定しない。
        val point = runCatching { project(x, y) }.getOrNull() ?: return null
        if (!point.first.isFinite() || !point.second.isFinite() ||
            point.first !in -90.0..90.0 || point.second !in -180.0..180.0
        ) return null
        point
    }
    return AreaSelectionBounds(
        north = projected.maxOf { it.first },
        east = projected.maxOf { it.second },
        south = projected.minOf { it.first },
        west = projected.minOf { it.second },
    )
}
