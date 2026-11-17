package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.routing.ManeuverType
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.routing.bearingBetween
import com.gorite.cyclemap.routing.haversineMeters

/**
 * サイコン画面の「目的地」欄の値を作る (純粋な関数)。
 * - 目的地が無い → null
 * - ルートと進捗がある → 道なりの残り距離と、次の曲がり角
 * - それ以外 → 直線距離と、進行方向に対する目的地の向き ([courseDegrees] が無ければ向きは null)
 */
fun buildCycleComputerDestination(
    destLat: Double?,
    destLon: Double?,
    lat: Double?,
    lon: Double?,
    courseDegrees: Double?,
    progress: RouteProgress?,
): CycleComputerDestination? {
    if (destLat == null || destLon == null) return null
    if (progress != null) {
        val remaining = (progress.routeDistanceMeters - progress.distanceFromStartMeters).coerceAtLeast(0.0)
        val turn = progress.nextManeuver
        return CycleComputerDestination(
            remainingMeters = remaining,
            isStraightLine = false,
            nextTurnText = turn?.let { maneuverText(it.type) },
            nextTurnDistanceM = turn?.let { progress.distanceToNextManeuverMeters },
        )
    }
    if (lat == null || lon == null) return null
    val straight = haversineMeters(lat, lon, destLat, destLon)
    val bearing = bearingBetween(lat, lon, destLat, destLon)
    val relative = if (bearing != null && courseDegrees != null && courseDegrees.isFinite()) {
        normalizeRelativeBearing(bearing - courseDegrees)
    } else null
    return CycleComputerDestination(remainingMeters = straight, isStraightLine = true, relativeBearingDegrees = relative)
}

/** [-180, 180) に正規化する。0 = 真正面、+ = 右、- = 左。 */
fun normalizeRelativeBearing(degrees: Double): Double = ((degrees % 360.0) + 540.0) % 360.0 - 180.0

private fun maneuverText(type: ManeuverType): String = when (type) {
    ManeuverType.RIGHT -> "右折です"
    ManeuverType.LEFT -> "左折です"
    ManeuverType.U_TURN -> "Uターンです"
}
