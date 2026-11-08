package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructureElevationTest {
    private data class W(val tags: Map<String, String>, val ids: LongArray)

    /** 東西に 100m 間隔で並ぶノード (id 1..n)。緯度 34 度で経度 0.001 度 ≒ 92m。 */
    private fun line(n: Int): Map<Long, GraphNode> =
        (1..n).associate { it.toLong() to GraphNode(it.toLong(), 34.0, 132.0 + 0.001 * (it - 1)) }

    private fun structures(vararg ways: W) = StructureWays.from(ways.toList(), { it.tags }, { it.ids })

    private val road = mapOf("highway" to "secondary")
    private val tunnel = road + ("tunnel" to "yes")
    private val bridge = road + ("bridge" to "yes")

    @Test
    fun `構造物の判定`() {
        assertTrue(isStructureWay(tunnel))
        assertTrue(isStructureWay(bridge))
        assertTrue(isStructureWay(road + ("bridge" to "viaduct")))
        assertFalse(isStructureWay(road))
        assertFalse(isStructureWay(road + ("tunnel" to "building_passage")))
        assertFalse(isStructureWay(road + ("tunnel" to "culvert")))
        assertFalse(isStructureWay(road + ("bridge" to "no")))
    }

    @Test
    fun `トンネルの途中は山の標高ではなく坑口から線形補間する`() {
        val nodes = line(7)
        // 1-2 地上、2..6 トンネル、6-7 地上。DEM はトンネルの上の山 (300m) を返す
        val dem = mapOf(1L to 9f, 2L to 10f, 3L to 300f, 4L to 320f, 5L to 310f, 6L to 50f, 7L to 51f)
        val s = structures(W(road, longArrayOf(1, 2)), W(tunnel, longArrayOf(2, 3, 4, 5, 6)), W(road, longArrayOf(6, 7)))
        val (e, stats) = interpolateStructureElevations(dem, s, nodes)
        assertEquals(10f, e[2L]) // 坑口は DEM のまま
        assertEquals(50f, e[6L])
        assertEquals(20f, e.getValue(3L), 0.2f)
        assertEquals(30f, e.getValue(4L), 0.2f)
        assertEquals(40f, e.getValue(5L), 0.2f)
        assertEquals(9f, e[1L]) // 構造物でないノードは変えない
        assertEquals(3, stats.interpolated)
    }

    @Test
    fun `複数の way に分かれたトンネルも一続きに補間する`() {
        val nodes = line(5)
        val dem = mapOf(1L to 0f, 2L to 200f, 3L to 400f, 4L to 200f, 5L to 40f)
        // 1 と 5 が坑口 (地上の道路につながる)、3 は 2 本のトンネル way の境目
        val s = structures(
            W(road, longArrayOf(1)), W(tunnel, longArrayOf(1, 2, 3)), W(tunnel, longArrayOf(3, 4, 5)), W(road, longArrayOf(5)),
        )
        val (e, _) = interpolateStructureElevations(dem, s, nodes)
        assertEquals(10f, e.getValue(2L), 0.2f)
        assertEquals(20f, e.getValue(3L), 0.2f)
        assertEquals(30f, e.getValue(4L), 0.2f)
    }

    @Test
    fun `橋の途中は谷底ではなく橋台から補間する`() {
        val nodes = line(4)
        val dem = mapOf(1L to 60f, 2L to 5f, 3L to 3f, 4L to 63f)
        val s = structures(W(road, longArrayOf(1)), W(bridge, longArrayOf(1, 2, 3, 4)), W(road, longArrayOf(4)))
        val (e, _) = interpolateStructureElevations(dem, s, nodes)
        assertEquals(61f, e.getValue(2L), 0.2f)
        assertEquals(62f, e.getValue(3L), 0.2f)
    }

    @Test
    fun `両端の標高が不明な構造物は不明にする`() {
        val nodes = line(3)
        val dem = mapOf(1L to Float.NaN, 2L to 0f, 3L to Float.NaN) // 海の上の橋
        val s = structures(W(road, longArrayOf(1)), W(bridge, longArrayOf(1, 2, 3)), W(road, longArrayOf(3)))
        val (e, stats) = interpolateStructureElevations(dem, s, nodes)
        assertTrue(e.getValue(2L).isNaN())
        assertEquals(1, stats.unknown)
    }

    @Test
    fun `片側だけ標高が分かれば、その値で一定にする`() {
        val nodes = line(3)
        val dem = mapOf(1L to 12f, 2L to 0f, 3L to Float.NaN)
        val s = structures(W(road, longArrayOf(1)), W(bridge, longArrayOf(1, 2, 3)), W(road, longArrayOf(3)))
        val (e, _) = interpolateStructureElevations(dem, s, nodes)
        assertEquals(12f, e.getValue(2L), 0.01f)
    }

    @Test
    fun `枝分かれのある構造物 (立体交差の橋) も固定点の間で滑らかに解く`() {
        // 2 から 3 本の橋が出る Y 字。端 1・3・4 は地上
        val nodes = mapOf(
            1L to GraphNode(1, 34.0, 132.000),
            2L to GraphNode(2, 34.0, 132.001),
            3L to GraphNode(3, 34.0, 132.002),
            4L to GraphNode(4, 34.001, 132.001),
        )
        val dem = mapOf(1L to 10f, 2L to 0f, 3L to 10f, 4L to 10f)
        val s = structures(
            W(road, longArrayOf(1)), W(road, longArrayOf(3)), W(road, longArrayOf(4)),
            W(bridge, longArrayOf(1, 2, 3)), W(bridge, longArrayOf(2, 4)),
        )
        val (e, _) = interpolateStructureElevations(dem, s, nodes)
        assertEquals(10f, e.getValue(2L), 0.01f)
    }

    @Test
    fun `構造物の行き止まりは DEM のまま固定点にする`() {
        val nodes = line(3)
        val dem = mapOf(1L to 10f, 2L to 100f, 3L to 30f)
        // 3 は地上の道路につながらない行き止まり (切り出し範囲の端など)
        val s = structures(W(road, longArrayOf(1)), W(tunnel, longArrayOf(1, 2, 3)))
        val (e, _) = interpolateStructureElevations(dem, s, nodes)
        assertEquals(30f, e[3L])
        assertEquals(20f, e.getValue(2L), 0.2f)
    }
}
