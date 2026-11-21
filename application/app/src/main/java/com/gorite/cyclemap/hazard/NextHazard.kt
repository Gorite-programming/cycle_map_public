package com.gorite.cyclemap.hazard

const val CROSSING_NOTICE_DISTANCE_METERS = 500.0
const val BRIDGE_NOTICE_DISTANCE_METERS = 1000.0

/**
 * 現在の走行距離に対して次に表示すべき注意地点の情報。
 */
data class HazardAhead(
    val kind: HazardKind,
    val name: String?,
    val distanceMeters: Double,
    val inside: Boolean,
    val remainingMeters: Double,
)

/**
 * まだ通り終えていない (`endMeters > traveledMeters`) 最初の注意地点を選び、
 * 踏切は 500 m 以内、橋は 1000 m 以内のときだけ [HazardAhead] を返す。
 */
fun nextHazardAhead(hazards: List<RouteHazard>, traveledMeters: Double): HazardAhead? {
    if (hazards.isEmpty() || traveledMeters.isNaN()) return null

    var next: RouteHazard? = null
    for (hazard in hazards) {
        if (hazard.endMeters > traveledMeters) {
            if (next == null ||
                hazard.startMeters < next.startMeters ||
                (hazard.startMeters == next.startMeters && hazard.endMeters < next.endMeters)
            ) {
                next = hazard
            }
        }
    }
    if (next == null) return null

    val noticeLimit = when (next.kind) {
        HazardKind.CROSSING -> CROSSING_NOTICE_DISTANCE_METERS
        HazardKind.BRIDGE -> BRIDGE_NOTICE_DISTANCE_METERS
    }
    val delta = next.startMeters - traveledMeters
    if (delta > noticeLimit) return null

    val inside = next.kind == HazardKind.BRIDGE && next.startMeters <= traveledMeters
    return if (inside) {
        HazardAhead(
            kind = next.kind,
            name = next.name,
            distanceMeters = 0.0,
            inside = true,
            remainingMeters = next.endMeters - traveledMeters,
        )
    } else {
        HazardAhead(
            kind = next.kind,
            name = next.name,
            distanceMeters = delta.coerceAtLeast(0.0),
            inside = false,
            remainingMeters = 0.0,
        )
    }
}
