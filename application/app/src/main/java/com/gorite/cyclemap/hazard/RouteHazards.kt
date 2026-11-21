package com.gorite.cyclemap.hazard

import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.utils.haversineMeters
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

enum class HazardKind {
    CROSSING,
    BRIDGE,
}

/**
 * 経路上に照合された 1 件の注意地点 (始点からの距離範囲つき)。
 */
data class RouteHazard(
    val kind: HazardKind,
    val name: String?,
    val startMeters: Double,
    val endMeters: Double,
)

const val CROSSING_MATCH_METERS = 12.0
const val BRIDGE_VERTEX_MATCH_METERS = 10.0
const val BRIDGE_MIN_OVERLAP_METERS = 20.0
const val MERGE_GAP_METERS = 40.0

private const val METERS_PER_DEG = 6_371_000.0 * Math.PI / 180.0
private const val ROUTE_CELL_DEG = 0.001
private const val MAX_GRID_CELLS_PER_SEGMENT = 16L
private const val QUERY_MARGIN_DEG = 0.001

/**
 * 経路の座標列 [route] と注意地点索引 [index] を照合し、始点からの距離順で返す。
 */
fun findRouteHazards(route: List<RoutePoint>, index: HazardIndex): List<RouteHazard> {
    if (route.size < 2) return emptyList()

    val cumulativeMeters = DoubleArray(route.size)
    var minLat = route[0].latitude
    var maxLat = route[0].latitude
    var minLon = route[0].longitude
    var maxLon = route[0].longitude

    for (i in 1 until route.size) {
        val prev = route[i - 1]
        val curr = route[i]
        cumulativeMeters[i] = cumulativeMeters[i - 1] +
            haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
        if (curr.latitude < minLat) minLat = curr.latitude
        if (curr.latitude > maxLat) maxLat = curr.latitude
        if (curr.longitude < minLon) minLon = curr.longitude
        if (curr.longitude > maxLon) maxLon = curr.longitude
    }

    val midLat = (minLat + maxLat) * 0.5
    val lonMargin = QUERY_MARGIN_DEG / cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
    val candidates = index.query(
        minLat - QUERY_MARGIN_DEG,
        minLon - lonMargin,
        maxLat + QUERY_MARGIN_DEG,
        maxLon + lonMargin,
    )
    if (candidates.crossingCount == 0 && candidates.bridges.isEmpty()) return emptyList()

    val routeMatcher = RouteSegmentMatcher(route, cumulativeMeters)

    val crossings = ArrayList<RouteHazard>()
    for (i in 0 until candidates.crossingCount) {
        val along = routeMatcher.nearestAlongMeters(
            candidates.crossingLatitudes[i],
            candidates.crossingLongitudes[i],
            CROSSING_MATCH_METERS,
        )
        if (!along.isNaN()) {
            crossings.add(RouteHazard(HazardKind.CROSSING, null, along, along))
        }
    }

    val bridges = ArrayList<RouteHazard>()
    for (bridge in candidates.bridges) {
        var matchedVertices = 0
        var minAlong = Double.POSITIVE_INFINITY
        var maxAlong = Double.NEGATIVE_INFINITY
        val lats = bridge.latitudes
        val lons = bridge.longitudes
        for (k in lats.indices) {
            val along = routeMatcher.nearestAlongMeters(lats[k], lons[k], BRIDGE_VERTEX_MATCH_METERS)
            if (!along.isNaN()) {
                matchedVertices++
                if (along < minAlong) minAlong = along
                if (along > maxAlong) maxAlong = along
            }
        }
        if (matchedVertices >= 2 && (maxAlong - minAlong) >= BRIDGE_MIN_OVERLAP_METERS) {
            bridges.add(RouteHazard(HazardKind.BRIDGE, bridge.name, minAlong, maxAlong))
        }
    }

    val mergedCrossings = mergeSameKind(crossings)
    val mergedBridges = mergeSameKind(bridges)
    if (mergedCrossings.isEmpty() && mergedBridges.isEmpty()) return emptyList()

    val combined = ArrayList<RouteHazard>(mergedCrossings.size + mergedBridges.size)
    combined.addAll(mergedCrossings)
    combined.addAll(mergedBridges)
    combined.sortWith(compareBy<RouteHazard>({ it.startMeters }, { it.endMeters }, { it.kind.ordinal }))
    return combined
}

private fun mergeSameKind(items: MutableList<RouteHazard>): List<RouteHazard> {
    if (items.size <= 1) return items
    items.sortWith(compareBy<RouteHazard>({ it.startMeters }, { it.endMeters }))
    val merged = ArrayList<RouteHazard>(items.size)
    var current = items[0]
    for (i in 1 until items.size) {
        val next = items[i]
        if (next.startMeters - current.endMeters <= MERGE_GAP_METERS) {
            current = RouteHazard(
                kind = current.kind,
                name = current.name ?: next.name,
                startMeters = current.startMeters,
                endMeters = max(current.endMeters, next.endMeters),
            )
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}

/**
 * 経路の各線分を 0.001° (約 111 m) グリッドに索引付けし、任意座標の最寄り射影位置を求める。
 */
private class RouteSegmentMatcher(
    private val route: List<RoutePoint>,
    private val cumulativeMeters: DoubleArray,
) {
    private val grid = HashMap<Long, IntArray>()
    private val longSegmentIndices: IntArray

    init {
        val builders = HashMap<Long, IntArrayBuilder>()
        val longSegments = IntArrayBuilder()
        for (s in 0 until route.size - 1) {
            val a = route[s]
            val b = route[s + 1]
            val minLatCell = gridCellOf(min(a.latitude, b.latitude), ROUTE_CELL_DEG)
            val maxLatCell = gridCellOf(max(a.latitude, b.latitude), ROUTE_CELL_DEG)
            val minLonCell = gridCellOf(min(a.longitude, b.longitude), ROUTE_CELL_DEG)
            val maxLonCell = gridCellOf(max(a.longitude, b.longitude), ROUTE_CELL_DEG)
            val span = (maxLatCell.toLong() - minLatCell.toLong() + 1L) *
                (maxLonCell.toLong() - minLonCell.toLong() + 1L)
            if (span <= MAX_GRID_CELLS_PER_SEGMENT) {
                for (latCell in minLatCell..maxLatCell) {
                    for (lonCell in minLonCell..maxLonCell) {
                        val key = gridCellKey(latCell, lonCell)
                        builders.getOrPut(key) { IntArrayBuilder() }.add(s)
                    }
                }
            } else {
                longSegments.add(s)
            }
        }
        for ((key, builder) in builders) {
            grid[key] = builder.toIntArray()
        }
        longSegmentIndices = longSegments.toIntArray()
    }

    fun nearestAlongMeters(lat: Double, lon: Double, maxDistMeters: Double): Double {
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val latDelta = maxDistMeters / METERS_PER_DEG
        val lonDelta = maxDistMeters / (METERS_PER_DEG * cosLat)

        val minLatCell = gridCellOf(lat - latDelta, ROUTE_CELL_DEG)
        val maxLatCell = gridCellOf(lat + latDelta, ROUTE_CELL_DEG)
        val minLonCell = gridCellOf(lon - lonDelta, ROUTE_CELL_DEG)
        val maxLonCell = gridCellOf(lon + lonDelta, ROUTE_CELL_DEG)

        val maxDistSq = maxDistMeters * maxDistMeters
        var bestDistSq = Double.POSITIVE_INFINITY
        var bestSeg = -1
        var bestT = 0.0

        fun evaluateSegments(segments: IntArray) {
            for (s in segments) {
                val a = route[s]
                val b = route[s + 1]
                val ax = (a.longitude - lon) * METERS_PER_DEG * cosLat
                val ay = (a.latitude - lat) * METERS_PER_DEG
                val bx = (b.longitude - lon) * METERS_PER_DEG * cosLat
                val by = (b.latitude - lat) * METERS_PER_DEG
                val dx = bx - ax
                val dy = by - ay
                val lenSq = dx * dx + dy * dy
                val t = if (lenSq <= 0.0) 0.0 else ((-ax * dx - ay * dy) / lenSq).coerceIn(0.0, 1.0)
                val cx = ax + t * dx
                val cy = ay + t * dy
                val distSq = cx * cx + cy * cy
                if (distSq <= maxDistSq && distSq < bestDistSq) {
                    bestDistSq = distSq
                    bestSeg = s
                    bestT = t
                }
            }
        }

        for (latCell in minLatCell..maxLatCell) {
            for (lonCell in minLonCell..maxLonCell) {
                val bucket = grid[gridCellKey(latCell, lonCell)] ?: continue
                evaluateSegments(bucket)
            }
        }
        if (longSegmentIndices.isNotEmpty()) {
            evaluateSegments(longSegmentIndices)
        }

        if (bestSeg < 0) return Double.NaN
        return cumulativeMeters[bestSeg] + (cumulativeMeters[bestSeg + 1] - cumulativeMeters[bestSeg]) * bestT
    }
}
