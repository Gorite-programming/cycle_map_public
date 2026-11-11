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
import kotlin.math.abs

class ContractionHierarchyTest {
    private data class E(val to: Int, val meters: Double, val type: String = "residential", val grade: Double? = 0.0)
    private fun fixture(): LazyMappedRoadGraph {
        // Directed arcs model one-way roads; node 6 is disconnected. The motorway arc is forbidden.
        val out = listOf(
            listOf(E(1, 10.0), E(2, 20.0), E(3, 10.0)),
            listOf(E(2, 10.0)),
            listOf(E(4, 10.0)),
            listOf(E(2, 10.0)),
            listOf(E(5, 10.0), E(6, 1.0, "motorway", null)),
            emptyList(), emptyList(),
        )
        val bytes = ByteArrayOutputStream(); val starts = LongArray(out.size); val first = IntArray(out.size); val counts = IntArray(out.size); val targets = ArrayList<Int>()
        DataOutputStream(bytes).use { d -> out.forEachIndexed { u, edges ->
            starts[u] = bytes.size().toLong(); first[u] = targets.size; counts[u] = edges.size
            for (e in edges) {
                d.writeLong(u.toLong()); d.writeLong(e.to.toLong()); d.writeDouble(e.meters)
                val type = e.type.toByteArray(); d.writeShort(type.size); d.write(type); d.writeByte(0)
                if (e.grade == null) d.writeByte(0) else { d.writeByte(1); d.writeDouble(e.grade) }
                targets += e.to
            }
        } }
        val tmp = File.createTempFile("ch-test", ".graph"); tmp.deleteOnExit(); val channel = RandomAccessFile(tmp, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java, DoubleArray::class.java, DoubleArray::class.java, LongArray::class.java, IntArray::class.java, IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        return ctor.newInstance(channel, ByteBuffer.wrap(bytes.toByteArray()), LongArray(out.size) { it.toLong() }, DoubleArray(out.size), DoubleArray(out.size), starts, first, counts, targets.toIntArray(), targets.size) as LazyMappedRoadGraph
    }

    @Test fun allPairsMatchAStarAndSerializationRoundTrips() {
        fixture().use { graph ->
            val built = ContractionHierarchyBuilder(maxWitnessSettled = 2).build(graph, GraphFingerprint.UNKNOWN)
            assertTrue(built.shortcutCount > 0, "fixture should exercise shortcut expansion")
            val ch = built.hierarchy
            val tmp = File.createTempFile("ch-test", ".graph.ch"); tmp.deleteOnExit(); ch.write(tmp); val loaded = ContractionHierarchy.read(tmp, GraphFingerprint.UNKNOWN)
            for (s in 0 until graph.nodeCount) for (g in 0 until graph.nodeCount) {
                val a = graph.route(s, g, preference = RoutePreference.RECOMMENDED, costMode = RouteCostMode.BICYCLE)
                val c = loaded.route(s, g, graph)
                assertEquals(a.isReachable, c.isReachable, "$s->$g reachability")
                if (a.isReachable) {
                    assertTrue(abs(a.totalCost - c.totalCost) <= maxOf(1e-12, abs(a.totalCost) * 1e-9), "$s->$g cost ${a.totalCost} != ${c.totalCost}")
                    assertEquals(a.totalDistanceMeters, c.totalDistance, 1e-9, "$s->$g distance")
                    assertEquals(s, c.nodeIndices.first()); assertEquals(g, c.nodeIndices.last())
                }
            }
            assertTrue(loaded.route(0, 0, graph).isReachable)
            assertFalse(loaded.route(1, 0, graph).isReachable) // directed one-way
            assertFalse(loaded.route(0, 6, graph).isReachable) // motorway is excluded
            assertFalse(loaded.route(6, 0, graph).isReachable) // disconnected
        }
    }

    @Test fun nestedShortcutsUnpackToEveryOriginalNode() {
        val chain = List(8) { u -> if (u == 7) emptyList() else listOf(E(u + 1, 1.0)) }
        graphFrom(chain).use { graph ->
            val built = ContractionHierarchyBuilder(maxWitnessSettled = 1).build(graph, GraphFingerprint.UNKNOWN)
            assertTrue(built.shortcutCount >= 2)
            val result = built.hierarchy.route(0, 7, graph)
            assertTrue(result.nodeIndices.contentEquals(IntArray(8) { it }), result.nodeIndices.contentToString())
        }
    }

    @Test fun parallelEdgesKeepMinimumCostAndDistance() {
        val out = listOf(
            listOf(E(1, 30.0), E(1, 10.0), E(1, 10.0)),
            listOf(E(2, 8.0)), emptyList(),
        )
        graphFrom(out).use { graph ->
            val ch = ContractionHierarchyBuilder(maxWitnessSettled = 100).build(graph, GraphFingerprint.UNKNOWN).hierarchy
            val result = ch.route(0, 2, graph)
            val aStar = graph.route(0, 2, preference = RoutePreference.RECOMMENDED, costMode = RouteCostMode.BICYCLE)
            assertEquals(aStar.totalCost, result.totalCost, 1e-9)
            assertEquals(18.0, result.totalDistance, 1e-9)
        }
    }

    @Test fun graphIdentityMismatchRejectsRead() {
        fixture().use { graph ->
            val chFile = File.createTempFile("ch-test", ".graph.ch").apply { deleteOnExit() }
            val sourceGraph = File.createTempFile("ch-source", ".graph").apply { writeText("source graph A"); deleteOnExit() }
            val otherGraph = File.createTempFile("ch-other", ".graph").apply { writeText("source graph B"); deleteOnExit() }
            ContractionHierarchyBuilder(maxWitnessSettled = 2).build(graph, GraphFingerprint.fromFile(sourceGraph)).hierarchy.write(chFile)
            ContractionHierarchy.read(chFile, sourceGraph)
            kotlin.test.assertFailsWith<IllegalArgumentException> { ContractionHierarchy.read(chFile, otherGraph) }
        }
    }

    @Test fun directedCyclicGraphMatchesAStarForEveryPair() {
        // A denser directed graph creates several alternative upward peaks and forces
        // backward CH search to traverse the reverse of upward arcs.
        val n = 24
        val out = List(n) { u ->
            val edges = ArrayList<E>()
            for (step in 1..5) {
                val v = (u * 7 + step * 11 + 3) % n
                if (v != u) edges += E(v, 4.0 + ((u * 19 + v * 13 + step) % 37))
            }
            edges
        }
        graphFrom(out).use { graph ->
            val ch = ContractionHierarchyBuilder(maxWitnessSettled = 1000).build(graph, GraphFingerprint.UNKNOWN).hierarchy
            for (s in 0 until n) for (g in 0 until n) {
                val a = graph.route(s, g, preference = RoutePreference.RECOMMENDED, costMode = RouteCostMode.BICYCLE)
                val c = ch.route(s, g, graph)
                assertEquals(a.isReachable, c.isReachable, "$s->$g reachability")
                if (a.isReachable) assertTrue(abs(a.totalCost - c.totalCost) <= maxOf(1e-12, abs(a.totalCost) * 1e-9), "$s->$g cost ${a.totalCost} != ${c.totalCost}")
            }
        }
    }

    private fun graphFrom(out: List<List<E>>): LazyMappedRoadGraph {
        val bytes = ByteArrayOutputStream(); val starts = LongArray(out.size); val first = IntArray(out.size); val counts = IntArray(out.size); val targets = ArrayList<Int>()
        DataOutputStream(bytes).use { d -> out.forEachIndexed { u, edges ->
            starts[u] = bytes.size().toLong(); first[u] = targets.size; counts[u] = edges.size
            for (e in edges) {
                d.writeLong(u.toLong()); d.writeLong(e.to.toLong()); d.writeDouble(e.meters)
                val type = e.type.toByteArray(); d.writeShort(type.size); d.write(type); d.writeByte(0)
                if (e.grade == null) d.writeByte(0) else { d.writeByte(1); d.writeDouble(e.grade) }
                targets += e.to
            }
        } }
        val tmp = File.createTempFile("ch-test", ".graph"); tmp.deleteOnExit(); val channel = RandomAccessFile(tmp, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java, DoubleArray::class.java, DoubleArray::class.java, LongArray::class.java, IntArray::class.java, IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        return ctor.newInstance(channel, ByteBuffer.wrap(bytes.toByteArray()), LongArray(out.size) { it.toLong() }, DoubleArray(out.size), DoubleArray(out.size), starts, first, counts, targets.toIntArray(), targets.size) as LazyMappedRoadGraph
    }
}
