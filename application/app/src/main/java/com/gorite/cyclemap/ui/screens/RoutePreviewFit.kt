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
internal fun previewPaddingPx(screenHeightPx: Int, density: Float, waypointCount: Int = 0): RoutePreviewPaddingPx {
    require(screenHeightPx > 0) { "screenHeightPx must be positive" }
    require(density > 0f) { "density must be positive" }

    // Keep clear of the search bar and banners (previously a fixed 220dp).
    val top = max((screenHeightPx * 0.10).toInt(), (180f * density).toInt())
    // The route-preview sheet is about 490dp tall; keep the whole route above it.
    // Each waypoint row in the sheet adds about 60dp.
    val preferredBottom = ((490f + 60f * waypointCount.coerceAtLeast(0)) * density).toInt()
    // Preserve a usable camera viewport on compact windows, even when the 280dp
    // preferred minimum would otherwise consume more than 80% of its height.
    val maxBottomForViewport = (screenHeightPx * 0.80).toInt() - top
    val bottom = preferredBottom.coerceAtMost(maxBottomForViewport.coerceAtLeast(0))

    return RoutePreviewPaddingPx(
        top = top,
        bottom = bottom,
        side = (80f * density).toInt(),
    )
}
