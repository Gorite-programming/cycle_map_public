package com.gorite.cyclemap.tracking

/** Filters for cycle-computer statistics only; GPX keeps its existing location stream. */
internal object RideMotionPolicy {
    const val MAX_ACCURACY_M = 20f
    const val MIN_DISTANCE_M = 3.0
    // 自動一時停止の閾値 (2.0 km/h ≒ 0.556 m/s) と整合させ、押し歩き・急な登りも拾う
    const val MIN_SPEED_MPS = 0.5

    fun acceptsAccuracy(accuracyM: Float): Boolean =
        accuracyM.isFinite() && accuracyM in 0f..MAX_ACCURACY_M

    fun speedMps(rawSpeed: Double, stepM: Double?, hasReportedSpeed: Boolean, previousSpeed: Double = 0.0): Double = when {
        !rawSpeed.isFinite() || rawSpeed < MIN_SPEED_MPS -> 0.0
        !hasReportedSpeed && (stepM == null || stepM < MIN_DISTANCE_M) ->
            previousSpeed.takeIf { it.isFinite() && it >= MIN_SPEED_MPS } ?: 0.0
        else -> rawSpeed
    }

    fun acceptsDistance(distanceM: Double, speedMps: Double): Boolean =
        distanceM.isFinite() && distanceM >= MIN_DISTANCE_M && speedMps.isFinite() && speedMps >= MIN_SPEED_MPS
}
