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

class CrossGraphRoutingTest {
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

    private val coords = mapOf(
        10L to (34.0000 to 132.0000), 20L to (34.0002 to 132.0002),
        30L to (34.0004 to 132.0004), 40L to (34.0006 to 132.0006),
        50L to (34.0008 to 132.0008),
    )
    private val allEdges = mapOf(
        10L to listOf(Edge(20, 50.0, 3.0), Edge(40, 80.0, 0.0)),
        20L to listOf(Edge(30, 70.0, -2.0)),
        30L to listOf(Edge(50, 80.0, -1.0)),
        40L to listOf(Edge(50, 400.0, 5.0)),
    )

    @Test
    fun acrossRouteMatchesCombinedGraphForEveryPreference() {
        graph(longArrayOf(10, 20, 30, 40, 50), coords, allEdges).use { combined ->
            graph(longArrayOf(10, 20, 30, 40), coords, allEdges.filterKeys { it != 30L && it != 40L }.plus(30L to emptyList()).plus(40L to emptyList())).use { a ->
                graph(longArrayOf(30, 40, 50), coords, mapOf(30L to allEdges.getValue(30), 40L to allEdges.getValue(40))).use { b ->
                    val portals = portalsBetween(a, b)
                    assertEquals(listOf(30L, 40L), portals.nodeIds.toList())
                    for (preference in RoutePreference.entries) for (costMode in RouteCostMode.entries) {
                        val expected = combined.route(0, 4, preference = preference, costMode = costMode)
                        val actual = routeAcrossGraphs(a, 0, b, 2, portals, preference = preference, costMode = costMode)
                        assertTrue(expected.isReachable)
                        assertTrue(actual.isReachable)
                        assertEquals(expected.totalCost, actual.totalCost, 1e-6, "$preference/$costMode cost")
                        assertEquals(expected.totalDistanceMeters, actual.totalDistanceMeters, 1e-6, "$preference/$costMode distance")
                        assertEquals(expected.nodeIds, actual.nodeIds, "$preference/$costMode nodes")
                        assertEquals(30L, actual.portalId, "A shortest portal is 40, but globally optimal portal is 30")
                        assertEquals(listOf(10L, 20L, 30L), actual.aPart.nodeIds)
                        assertEquals(listOf(30L, 50L), actual.bPart.nodeIds)
                    }
                }
            }
        }
    }

    @Test
    fun noCommonIdsIsUnreachableAndCancellationIsReported() {
        val c = coords + (60L to (34.0010 to 132.0010))
        graph(longArrayOf(10, 20), c, mapOf(10L to listOf(Edge(20, 100.0)))).use { a ->
            graph(longArrayOf(60), c, emptyMap()).use { b ->
                val none = portalsBetween(a, b)
                assertEquals(0, none.size)
                assertFalse(routeAcrossGraphs(a, 0, b, 0, none).isReachable)
                val cancelled = a.routeToPortals(0, intArrayOf(1), isCancelled = { true })
                assertTrue(cancelled.wasCancelled)
                graph(longArrayOf(20, 60), c, mapOf(20L to listOf(Edge(60, 100.0)))).use { bWithPortal ->
                    val shared = portalsBetween(a, bWithPortal)
                    val cancelledAcross = routeAcrossGraphs(a, 0, bWithPortal, 1, shared, isCancelled = { true })
                    assertTrue(cancelledAcross.wasCancelled)
                }
            }
        }
    }
}
