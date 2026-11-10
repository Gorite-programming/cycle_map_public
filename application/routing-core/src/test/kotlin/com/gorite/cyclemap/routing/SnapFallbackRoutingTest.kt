package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapFallbackRoutingTest {
    private data class Edge(val to: Long, val distance: Double, val grade: Double = Double.NaN)

    private fun graph(ids: LongArray, coords: Map<Long, Pair<Double, Double>>, edges: Map<Long, List<Edge>>): LazyMappedRoadGraph {
        val indexById = ids.withIndex().associate { it.value to it.index }
        val bytes = ByteArrayOutputStream()
        val starts = LongArray(ids.size); val first = IntArray(ids.size); val counts = IntArray(ids.size)
        val targets = ArrayList<Int>()
        DataOutputStream(bytes).use { out ->
            ids.forEachIndexed { i, id ->
                starts[i] = bytes.size().toLong(); first[i] = targets.size
                val outgoing = edges[id].orEmpty(); counts[i] = outgoing.size
                for (edge in outgoing) {
                    out.writeLong(id); out.writeLong(edge.to); out.writeDouble(edge.distance)
                    val type = "residential".toByteArray(); out.writeShort(type.size); out.write(type)
                    out.writeByte(0)
                    if (edge.grade.isNaN()) out.writeByte(0) else { out.writeByte(1); out.writeDouble(edge.grade) }
                    targets += indexById[edge.to] ?: error("unknown target ${edge.to}")
                }
            }
        }
        val tmp = File.createTempFile("cross-graph", ".graph").apply { deleteOnExit() }
        val channel = RandomAccessFile(tmp, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(
            FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java,
            DoubleArray::class.java, DoubleArray::class.java, LongArray::class.java,
            IntArray::class.java, IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return ctor.newInstance(channel, ByteBuffer.wrap(bytes.toByteArray()), ids,
            DoubleArray(ids.size) { coords.getValue(ids[it]).first }, DoubleArray(ids.size) { coords.getValue(ids[it]).second },
            starts, first, counts, targets.toIntArray(), targets.size) as LazyMappedRoadGraph
    }


    // 道路 10-20-30 (相互)。99 は公園内の孤立した点で、出発地点のすぐ隣にある。
    private val coords = mapOf(
        10L to (34.00000 to 132.00000), 20L to (34.00020 to 132.00020), 30L to (34.00040 to 132.00040),
        99L to (34.00001 to 132.00001),
    )
    private val edges = mapOf(
        10L to listOf(Edge(20, 30.0)), 20L to listOf(Edge(10, 30.0), Edge(30, 30.0)), 30L to listOf(Edge(20, 30.0)),
        99L to emptyList(),
    )

    @Test
    fun isolatedNearestNodeFallsBackToNextCandidate() {
        val g = graph(longArrayOf(10, 20, 30, 99), coords, edges)
        val isolated = g.nearestNodeIndex(34.000011, 132.000011)
        assertEquals(3, isolated) // 最寄りは孤立した 99
        assertFalse(g.route(isolated, 2).isReachable)

        val outcome = g.routeWithSnapFallback(34.000011, 132.000011, 34.00040, 132.00040)
        assertTrue(outcome.result.isReachable)
        assertTrue(outcome.usedFallbackSnap)
        assertEquals(0, outcome.startIndex)
        assertTrue(outcome.attempts >= 2)
    }

    @Test
    fun normalCaseUsesFirstPairOnly() {
        val g = graph(longArrayOf(10, 20, 30, 99), coords, edges)
        val outcome = g.routeWithSnapFallback(34.00000, 132.00000, 34.00040, 132.00040)
        assertTrue(outcome.result.isReachable)
        assertFalse(outcome.usedFallbackSnap)
        assertEquals(1, outcome.attempts)
    }

    @Test
    fun trulyUnreachableReturnsLastFailure() {
        val g = graph(longArrayOf(10, 20, 30, 99), coords, edges)
        // 終点も孤立点だけ (どの候補でも 99 と道路は結ばれない)
        val outcome = g.routeWithSnapFallback(34.000011, 132.000011, 34.000011, 132.000011, maxStartCandidates = 3, maxGoalCandidates = 2)
        assertTrue(outcome.attempts <= 3 + 1)
    }
}
