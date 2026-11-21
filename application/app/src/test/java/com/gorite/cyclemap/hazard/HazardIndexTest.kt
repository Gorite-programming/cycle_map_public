package com.gorite.cyclemap.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardIndexTest {

    @Test
    fun query_returnsOnlyItemsIntersectingBoundingBox() {
        val data = HazardData(
            crossingLatitudes = doubleArrayOf(34.391, 34.398, 34.420),
            crossingLongitudes = doubleArrayOf(132.461, 132.468, 132.490),
            bridges = listOf(
                // 範囲内
                BridgeHazard(
                    name = "対象橋",
                    latitudes = doubleArrayOf(34.392, 34.394),
                    longitudes = doubleArrayOf(132.462, 132.464),
                ),
                // 複数セルをまたぎ、bbox に一部が入る
                BridgeHazard(
                    name = "またぐ橋",
                    latitudes = doubleArrayOf(34.395, 34.415),
                    longitudes = doubleArrayOf(132.465, 132.465),
                ),
                // 完全に範囲外 (別セル)
                BridgeHazard(
                    name = "遠方の橋",
                    latitudes = doubleArrayOf(34.500, 34.505),
                    longitudes = doubleArrayOf(132.600, 132.605),
                ),
            ),
        )

        val index = HazardIndex.build(data)
        assertEquals(3, index.crossingCount)
        assertEquals(3, index.bridgeCount)

        // (34.398, 132.468) は同じ 0.01° セル (3439, 13246) に入るが bbox 外なので返らない
        val result = index.query(34.390, 132.460, 34.396, 132.466)
        assertEquals(1, result.crossingCount)
        assertEquals(34.391, result.crossingLatitudes[0], 1e-9)
        assertEquals(132.461, result.crossingLongitudes[0], 1e-9)

        assertEquals(2, result.bridges.size)
        assertEquals(setOf("対象橋", "またぐ橋"), result.bridges.map { it.name }.toSet())
    }

    @Test
    fun query_handlesMultiCellBridgeWithoutDuplicatesAndInvalidBbox() {
        val data = HazardData(
            crossingLatitudes = DoubleArray(0),
            crossingLongitudes = DoubleArray(0),
            bridges = listOf(
                BridgeHazard(
                    name = "長い橋",
                    latitudes = doubleArrayOf(34.385, 34.415),
                    longitudes = doubleArrayOf(132.455, 132.485),
                ),
            ),
        )
        val index = HazardIndex.build(data)

        // 複数セルに跨るクエリでも 1 本だけ返る
        val hit = index.query(34.380, 132.450, 34.420, 132.490)
        assertEquals(1, hit.bridges.size)
        assertEquals("長い橋", hit.bridges[0].name)

        // 広域クエリ (セル面積がグリッドエントリ数を大きく上回るケース) でも正常に動く
        val wideHit = index.query(30.0, 130.0, 40.0, 140.0)
        assertEquals(1, wideHit.bridges.size)

        // 反転 bbox は空を返す
        val inverted = index.query(35.0, 133.0, 34.0, 132.0)
        assertEquals(0, inverted.crossingCount)
        assertTrue(inverted.bridges.isEmpty())
    }
}
