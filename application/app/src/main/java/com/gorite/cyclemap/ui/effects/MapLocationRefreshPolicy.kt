package com.gorite.cyclemap.ui.effects

import kotlin.math.*

internal data class RefreshPosition(val latitude: Double, val longitude: Double)

internal fun refreshDistanceMeters(a: RefreshPosition, b: RefreshPosition): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
        cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
    return 12_742_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Compare against the last query, so many small movements still accumulate. */
internal fun shouldRefreshMapLocation(previous: RefreshPosition?, current: RefreshPosition): Boolean =
    previous == null || refreshDistanceMeters(previous, current) >= 50.0

/** Quiet fixes settle after one sampling interval; riding still makes progress within five seconds. */
internal fun shouldResolveAddress(
    previousSample: RefreshPosition?,
    current: RefreshPosition,
    pendingSinceMillis: Long,
    nowMillis: Long,
): Boolean = nowMillis - pendingSinceMillis >= 5_000L ||
    (nowMillis - pendingSinceMillis >= 1_000L && previousSample != null &&
        refreshDistanceMeters(previousSample, current) < 20.0)
