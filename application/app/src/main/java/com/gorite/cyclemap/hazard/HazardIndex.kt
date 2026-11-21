package com.gorite.cyclemap.hazard

import kotlin.math.floor

/**
 * 緯度経度 0.01° 四方 (約 1 km 角) の空間グリッドによる注意地点索引。
 */
class HazardIndex private constructor(
    private val data: HazardData,
    private val bridgeMinLat: DoubleArray,
    private val bridgeMaxLat: DoubleArray,
    private val bridgeMinLon: DoubleArray,
    private val bridgeMaxLon: DoubleArray,
    private val crossingGrid: Map<Long, IntArray>,
    private val bridgeGrid: Map<Long, IntArray>,
) {
    val crossingCount: Int get() = data.crossingCount
    val bridgeCount: Int get() = data.bridges.size

    /**
     * 指定されたバウンディングボックスと重なる踏切・橋だけを返す。
     */
    fun query(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): HazardData {
        if (minLat.isNaN() || minLon.isNaN() || maxLat.isNaN() || maxLon.isNaN()) return EMPTY_DATA
        if (minLat > maxLat || minLon > maxLon) return EMPTY_DATA

        val minLatCell = gridCellOf(minLat, CELL_SIZE_DEG)
        val maxLatCell = gridCellOf(maxLat, CELL_SIZE_DEG)
        val minLonCell = gridCellOf(minLon, CELL_SIZE_DEG)
        val maxLonCell = gridCellOf(maxLon, CELL_SIZE_DEG)
        val cellArea = (maxLatCell.toLong() - minLatCell.toLong() + 1L) *
            (maxLonCell.toLong() - minLonCell.toLong() + 1L)

        val matchedCrossings = IntArrayBuilder()
        collectMatchingCells(crossingGrid, minLatCell, maxLatCell, minLonCell, maxLonCell, cellArea) { bucket ->
            for (idx in bucket) {
                val lat = data.crossingLatitudes[idx]
                val lon = data.crossingLongitudes[idx]
                if (lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon) {
                    matchedCrossings.add(idx)
                }
            }
        }

        val matchedBridges = ArrayList<BridgeHazard>()
        val seenBridges = HashSet<Int>()
        collectMatchingCells(bridgeGrid, minLatCell, maxLatCell, minLonCell, maxLonCell, cellArea) { bucket ->
            for (idx in bucket) {
                if (bridgeMaxLat[idx] >= minLat &&
                    bridgeMinLat[idx] <= maxLat &&
                    bridgeMaxLon[idx] >= minLon &&
                    bridgeMinLon[idx] <= maxLon &&
                    seenBridges.add(idx)
                ) {
                    matchedBridges.add(data.bridges[idx])
                }
            }
        }

        val crossingIndices = matchedCrossings.toIntArray()
        val outCrossingLats = DoubleArray(crossingIndices.size)
        val outCrossingLons = DoubleArray(crossingIndices.size)
        for (i in crossingIndices.indices) {
            val idx = crossingIndices[i]
            outCrossingLats[i] = data.crossingLatitudes[idx]
            outCrossingLons[i] = data.crossingLongitudes[idx]
        }
        return HazardData(outCrossingLats, outCrossingLons, matchedBridges)
    }

    private inline fun collectMatchingCells(
        grid: Map<Long, IntArray>,
        minLatCell: Int,
        maxLatCell: Int,
        minLonCell: Int,
        maxLonCell: Int,
        cellArea: Long,
        onBucket: (IntArray) -> Unit,
    ) {
        if (grid.isEmpty()) return
        if (cellArea <= grid.size.toLong()) {
            for (latCell in minLatCell..maxLatCell) {
                for (lonCell in minLonCell..maxLonCell) {
                    val bucket = grid[gridCellKey(latCell, lonCell)] ?: continue
                    onBucket(bucket)
                }
            }
        } else {
            for ((key, bucket) in grid) {
                val latCell = gridKeyLat(key)
                val lonCell = gridKeyLon(key)
                if (latCell in minLatCell..maxLatCell && lonCell in minLonCell..maxLonCell) {
                    onBucket(bucket)
                }
            }
        }
    }

    companion object {
        const val CELL_SIZE_DEG = 0.01

        private val EMPTY_DATA = HazardData(DoubleArray(0), DoubleArray(0), emptyList())

        /** [HazardData] から 0.01° グリッドの索引を構築する。 */
        fun build(data: HazardData): HazardIndex {
            val crossingBuilders = HashMap<Long, IntArrayBuilder>()
            for (i in 0 until data.crossingCount) {
                val key = gridCellKey(
                    gridCellOf(data.crossingLatitudes[i], CELL_SIZE_DEG),
                    gridCellOf(data.crossingLongitudes[i], CELL_SIZE_DEG),
                )
                crossingBuilders.getOrPut(key) { IntArrayBuilder() }.add(i)
            }

            val bCount = data.bridges.size
            val bridgeMinLat = DoubleArray(bCount)
            val bridgeMaxLat = DoubleArray(bCount)
            val bridgeMinLon = DoubleArray(bCount)
            val bridgeMaxLon = DoubleArray(bCount)
            val bridgeBuilders = HashMap<Long, IntArrayBuilder>()

            for (i in 0 until bCount) {
                val bridge = data.bridges[i]
                var minLat = Double.POSITIVE_INFINITY
                var maxLat = Double.NEGATIVE_INFINITY
                var minLon = Double.POSITIVE_INFINITY
                var maxLon = Double.NEGATIVE_INFINITY
                val lats = bridge.latitudes
                val lons = bridge.longitudes
                for (k in lats.indices) {
                    val lat = lats[k]
                    val lon = lons[k]
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                }
                bridgeMinLat[i] = minLat
                bridgeMaxLat[i] = maxLat
                bridgeMinLon[i] = minLon
                bridgeMaxLon[i] = maxLon

                val cMinLat = gridCellOf(minLat, CELL_SIZE_DEG)
                val cMaxLat = gridCellOf(maxLat, CELL_SIZE_DEG)
                val cMinLon = gridCellOf(minLon, CELL_SIZE_DEG)
                val cMaxLon = gridCellOf(maxLon, CELL_SIZE_DEG)
                for (latCell in cMinLat..cMaxLat) {
                    for (lonCell in cMinLon..cMaxLon) {
                        val key = gridCellKey(latCell, lonCell)
                        bridgeBuilders.getOrPut(key) { IntArrayBuilder() }.add(i)
                    }
                }
            }

            return HazardIndex(
                data = data,
                bridgeMinLat = bridgeMinLat,
                bridgeMaxLat = bridgeMaxLat,
                bridgeMinLon = bridgeMinLon,
                bridgeMaxLon = bridgeMaxLon,
                crossingGrid = freezeGrid(crossingBuilders),
                bridgeGrid = freezeGrid(bridgeBuilders),
            )
        }

        private fun freezeGrid(builders: Map<Long, IntArrayBuilder>): Map<Long, IntArray> {
            if (builders.isEmpty()) return emptyMap()
            val frozen = HashMap<Long, IntArray>(builders.size)
            for ((key, builder) in builders) {
                frozen[key] = builder.toIntArray()
            }
            return frozen
        }
    }
}

internal fun gridCellOf(deg: Double, cellSizeDeg: Double): Int = floor(deg / cellSizeDeg).toInt()

internal fun gridCellKey(latCell: Int, lonCell: Int): Long =
    (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFFFFFFL)

internal fun gridKeyLat(key: Long): Int = (key shr 32).toInt()

internal fun gridKeyLon(key: Long): Int = key.toInt()

/** プリミティブ `Int` をボクシングなしで溜める小さな可変長配列。 */
internal class IntArrayBuilder(initialCapacity: Int = 4) {
    private var items = IntArray(initialCapacity)
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == items.size) {
            items = items.copyOf(items.size * 2)
        }
        items[size++] = value
    }

    fun toIntArray(): IntArray = if (size == items.size) items else items.copyOf(size)
}
