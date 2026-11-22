package com.gorite.cyclemap.ui.mapview

internal class ViewCenterSaveThrottle(private val minIntervalMillis: Long = 5_000L) {
    private var lastSavedAt: Long? = null
    private var lastSavedCenter: Pair<Double, Double>? = null
    private var pendingCenter: Pair<Double, Double>? = null

    fun onCenterChanged(center: Pair<Double, Double>, nowMillis: Long): Pair<Double, Double>? {
        pendingCenter = center.takeUnless { it == lastSavedCenter }
        val lastSave = lastSavedAt
        if (lastSave != null && nowMillis - lastSave < minIntervalMillis) return null
        return flush(nowMillis)
    }

    fun flush(nowMillis: Long): Pair<Double, Double>? {
        val center = pendingCenter ?: return null
        pendingCenter = null
        lastSavedAt = nowMillis
        lastSavedCenter = center
        return center
    }
}
