package com.gorite.cyclemap.ui.screens

import kotlin.math.cos
import kotlin.math.max

/** Geographic bounds used to keep very short route previews at a useful zoom. */
internal data class RoutePreviewBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

internal data class RoutePreviewPaddingPx(
    val top: Int,
    val bottom: Int,
    val side: Int,
)

/** Expands each axis around the original center until it spans at least [minSpanMeters]. */
internal fun expandBoundsToMinSpan(
    minLat: Double,
    maxLat: Double,
    minLon: Double,
    maxLon: Double,
    minSpanMeters: Double = 400.0,
): RoutePreviewBounds {
    require(minLat <= maxLat) { "minLat must be <= maxLat" }
    require(minLon <= maxLon) { "minLon must be <= maxLon" }
    require(minSpanMeters > 0.0) { "minSpanMeters must be positive" }

    val centerLat = (minLat + maxLat) / 2.0
    val centerLon = (minLon + maxLon) / 2.0
    val latitudeMetersPerDegree = 111_320.0
    val longitudeMetersPerDegree = latitudeMetersPerDegree *
        max(1e-6, cos(Math.toRadians(centerLat)))
    val requiredLatSpan = minSpanMeters / latitudeMetersPerDegree
    val requiredLonSpan = minSpanMeters / longitudeMetersPerDegree
    val latSpan = max(maxLat - minLat, requiredLatSpan)
    val lonSpan = max(maxLon - minLon, requiredLonSpan)

    return RoutePreviewBounds(
        minLat = centerLat - latSpan / 2.0,
        maxLat = centerLat + latSpan / 2.0,
        minLon = centerLon - lonSpan / 2.0,
        maxLon = centerLon + lonSpan / 2.0,
    )
}

/** Computes camera padding in pixels from the available screen height. */
internal fun previewPaddingPx(screenHeightPx: Int, density: Float, sheetHeightPx: Int): RoutePreviewPaddingPx {
    require(screenHeightPx > 0) { "screenHeightPx must be positive" }
    require(density > 0f) { "density must be positive" }

    // The search bar is hidden during the route preview, so only notification banners need clearance.
    val top = max((screenHeightPx * 0.08).toInt(), (60f * density).toInt())
        .coerceAtMost(screenHeightPx / 5)
    val preferredBottom = sheetHeightPx.coerceAtLeast(0) + (12f * density).toInt()
    val bottom = preferredBottom.coerceAtMost((screenHeightPx * 0.90).toInt() - top)

    return RoutePreviewPaddingPx(
        top = top,
        bottom = bottom,
        side = (80f * density).toInt(),
    )
}
