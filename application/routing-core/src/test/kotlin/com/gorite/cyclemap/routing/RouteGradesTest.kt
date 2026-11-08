package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteGradesTest {
    private data class E(val target: Int, val distance: Double, val grade: Double?)

    /** 座標の違うノードと、指定の辺 (勾配つき/なし) を持つ小さなグラフ。 */
    private fun graph(lats: DoubleArray, lons: DoubleArray, edges: List<List<E>>): LazyMappedRoadGraph {
        val bytes = ByteArrayOutputStream()
        val starts = LongArray(edges.size)
        val firsts = IntArray(edges.size)
        val counts = IntArray(edges.size)
        val targets = ArrayList<Int>()
        DataOutputStream(bytes).use { out ->
            edges.forEachIndexed { s, list ->
                starts[s] = bytes.size().toLong(); firsts[s] = targets.size; counts[s] = list.size
                list.forEach { e ->
                    out.writeLong(100L + s); out.writeLong(100L + e.target); out.writeDouble(e.distance)
                    val t = "residential".toByteArray(); out.writeShort(t.size); out.write(t)
                    out.writeByte(0)
                    if (e.grade != null) { out.writeByte(1); out.writeDouble(e.grade) } else out.writeByte(0)
                    targets += e.target
                }
            }
        }
        val tmp = File.createTempFile("routegrades", ".graph").apply { deleteOnExit() }
        val channel = RandomAccessFile(tmp, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(
            FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java, DoubleArray::class.java,
            DoubleArray::class.java, LongArray::class.java, IntArray::class.java, IntArray::class.java,
            IntArray::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return ctor.newInstance(
            channel, ByteBuffer.wrap(bytes.toByteArray()), LongArray(edges.size) { 100L + it }, lats, lons,
            starts, firsts, counts, targets.toIntArray(), targets.size,
        ) as LazyMappedRoadGraph
    }

    private val lats = doubleArrayOf(34.0, 34.001, 34.002, 34.003)
    private val lons = doubleArrayOf(132.0, 132.0, 132.0, 132.0)
    private val coords = lats.indices.map { lats[it] to lons[it] }

    @Test
    fun `辺の距離と勾配を経路の順に返す`() {
        graph(lats, lons, listOf(listOf(E(1, 111.0, 10.0)), listOf(E(2, 111.0, -5.0)), listOf(E(3, 111.0, null)), emptyList())).use { g ->
            val s = g.segmentGradesAlong(coords)
            assertEquals(3, s.size)
            assertEquals(10.0, s[0].gradePercent!!, 1e-6)
            assertEquals(-5.0, s[1].gradePercent!!, 1e-6)
            assertEquals(111.0, s[1].distanceMeters, 1e-9)
            assertNull(s[2].gradePercent) // 勾配なしの辺は不明
        }
    }

    @Test
    fun `グラフのノードから離れた点を含む区間は勾配不明で、直線距離を返す`() {
        graph(lats, lons, listOf(listOf(E(1, 111.0, 10.0)), listOf(E(2, 111.0, -5.0)), emptyList(), emptyList())).use { g ->
            val off = listOf(34.0 to 132.0, 34.001 to 132.01) // 2 点目は約 900m ずれている
            val s = g.segmentGradesAlong(off)
            assertEquals(1, s.size)
            assertNull(s[0].gradePercent)
        }
    }

    @Test
    fun `座標が2点未満なら空`() {
        graph(lats, lons, listOf(emptyList(), emptyList(), emptyList(), emptyList())).use { g ->
            assertEquals(emptyList(), g.segmentGradesAlong(listOf(34.0 to 132.0)))
        }
    }
}
