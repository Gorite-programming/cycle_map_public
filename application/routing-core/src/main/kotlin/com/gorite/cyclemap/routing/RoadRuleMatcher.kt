package com.gorite.cyclemap.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

data class RoadCandidate(
    val ordinal: Int,
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val info: BicycleRoadInfo?,
)

/** 近傍ノードから出る辺を候補にする ([includeOrdinary] が true なら特別なルールが無い通常の通行可能辺も含む)。 */
fun LazyMappedRoadGraph.roadCandidatesNear(
    lat: Double,
    lon: Double,
    radiusMeters: Double = 150.0,
    maxNodes: Int = 12,
    includeOrdinary: Boolean = true,
): List<RoadCandidate> = buildList {
    for (index in nearestNodeCandidates(lat, lon, radiusMeters, maxNodes)) {
        appendRoadCandidatesFrom(index, includeOrdinary, this)
    }
}

data class RoadRuleMatch(
    val ordinal: Int,
    val info: BicycleRoadInfo,
    val wrongWay: Boolean,
    val kind: RoadRuleKind?,
    val distanceMeters: Double,
)

/** GPS だけで道を区別できないときは、通行ルールを確定しない。 */
class RoadRuleMatcher {
    private data class Point(val lat: Double, val lon: Double)
    private data class Segment(val first: Point, val second: Point)
    private data class Scored(val candidate: RoadCandidate, val segment: Segment, val distance: Double, val delta: Double) {
        val cost: Double get() = distance + delta / 180.0 * 10.0
    }

    private var pending: Segment? = null
    private var consecutive = 0
    private var confirmedSegment: Segment? = null
    private var confirmed: RoadRuleMatch? = null
    private var invalidSince: Long? = null

    fun reset() {
        pending = null
        consecutive = 0
        confirmedSegment = null
        confirmed = null
        invalidSince = null
    }

    /** timeMs は単調増加する時刻を渡す。無効な入力が5秒続いたら確定も忘れる。 */
    fun update(
        timeMs: Long,
        lat: Double,
        lon: Double,
        accuracyM: Float,
        speedMps: Float?,
        bearingDeg: Float?,
        candidates: List<RoadCandidate>,
    ): RoadRuleMatch? {
        if (!lat.isFinite() || !lon.isFinite() || !accuracyM.isFinite() || accuracyM < 0 || accuracyM > 20 ||
            speedMps == null || !speedMps.isFinite() || speedMps < 1.5f || bearingDeg == null || !bearingDeg.isFinite()) {
            pending = null
            consecutive = 0
            val since = invalidSince ?: timeMs.also { invalidSince = it }
            if (timeMs - since >= 5_000) {
                confirmed = null
                confirmedSegment = null
            }
            return confirmed
        }
        invalidSince = null
        val scored = candidates.mapNotNull { score(it, lat, lon, bearingDeg.toDouble()) }
            .filter { it.distance <= max(accuracyM * 1.5, 15.0) }
        val best = scored.minByOrNull { it.cost }
        // 同じ線分の逆向きは除外する。僅差の別の道に異なるルールがあれば黙る (通常の通行可能辺 info == null は符号 0 扱い)。
        if (best == null || scored.any {
                it.segment != best.segment && it.cost - best.cost < 5.0 &&
                    (it.candidate.info?.encode() ?: 0) != (best.candidate.info?.encode() ?: 0)
            }) {
            reset()
            return null
        }
        if (pending == best.segment) consecutive = (consecutive + 1).coerceAtMost(3)
        else {
            pending = best.segment
            consecutive = 1
        }
        if (consecutive >= 3 || confirmedSegment == best.segment) {
            val info = best.candidate.info
            if (info == null) {
                confirmed = null
                confirmedSegment = best.segment
            } else {
                val wrongWay = best.delta > 135.0 && info.flags and BicycleRoadInfo.FLAG_ONEWAY != 0
                confirmed = RoadRuleMatch(best.candidate.ordinal, info, wrongWay, roadRuleKindOf(info, wrongWay), best.distance)
                confirmedSegment = best.segment
            }
        }
        return confirmed
    }

    private fun score(candidate: RoadCandidate, lat: Double, lon: Double, bearing: Double): Scored? {
        if (!candidate.fromLat.isFinite() || !candidate.fromLon.isFinite() ||
            !candidate.toLat.isFinite() || !candidate.toLon.isFinite()) return null
        // 現在地を原点にした局所平面。北を0度、東を90度とする。
        val metersPerDegree = Math.PI * 6_371_000.0 / 180.0
        val longitudeScale = metersPerDegree * cos(Math.toRadians(lat))
        val x = (candidate.fromLon - lon) * longitudeScale
        val y = (candidate.fromLat - lat) * metersPerDegree
        val dx = (candidate.toLon - candidate.fromLon) * longitudeScale
        val dy = (candidate.toLat - candidate.fromLat) * metersPerDegree
        val lengthSquared = dx * dx + dy * dy
        if (!lengthSquared.isFinite() || lengthSquared <= 0.0) return null
        val t = (-(x * dx + y * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val distance = hypot(x + t * dx, y + t * dy)
        val heading = Math.toDegrees(atan2(dx, dy))
        val difference = abs((bearing % 360.0 - heading) % 360.0)
        val delta = minOf(difference, 360.0 - difference)
        val from = Point(candidate.fromLat, candidate.fromLon)
        val to = Point(candidate.toLat, candidate.toLon)
        val segment = if (from.lat < to.lat || (from.lat == to.lat && from.lon <= to.lon)) Segment(from, to) else Segment(to, from)
        return Scored(candidate, segment, distance, delta)
    }
}
