package com.gorite.cyclemap.ui.mapview

internal fun mapMaxFps(navigationActive: Boolean, followLocation: Boolean, powerSave: Boolean): Int? =
    when {
        !navigationActive || !followLocation -> null
        powerSave -> 20
        else -> 30
    }

internal fun mapFrameIntervalNanos(maxFps: Int?): Long =
    if (maxFps == null) 0L else 1_000_000_000L / maxFps

internal fun isMapFrameDue(lastFrameNanos: Long?, frameNanos: Long, minIntervalNanos: Long): Boolean =
    lastFrameNanos == null || frameNanos - lastFrameNanos >= minIntervalNanos
