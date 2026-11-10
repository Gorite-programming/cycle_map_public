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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossChainRoutingTest {
    private data class Edge(val to: Long, val distance: Double, val grade: Double = Double.NaN)
    private val coords = mapOf(
        10L to (34.000 to 132.000), 20L to (34.001 to 132.001), 30L to (34.002 to 132.002),
        40L to (34.003 to 132.003), 50L to (34.004 to 132.004), 60L to (34.005 to 132.005),
    )
    private val edges = mapOf(
        10L to listOf(Edge(20, 80.0, 4.0), Edge(30, 30.0)),
        20L to listOf(Edge(40, 70.0, 2.0)),
        30L to listOf(Edge(50, 600.0, -2.0)),
        40L to listOf(Edge(60, 35.0, -1.0)),
        50L to listOf(Edge(60, 30.0)),
    )

    private fun graph(ids: LongArray, graphEdges: Map<Long, List<Edge>> = edges): LazyMappedRoadGraph {
        val indexes = ids.withIndex().associate { it.value to it.index }
        val bytes = ByteArrayOutputStream()
        val starts = LongArray(ids.size); val first = IntArray(ids.size); val counts = IntArray(ids.size)
        val targets = ArrayList<Int>()
        DataOutputStream(bytes).use { out -> ids.forEachIndexed { i, id ->
            starts[i] = bytes.size().toLong(); first[i] = targets.size
            val outgoing = graphEdges[id].orEmpty(); counts[i] = outgoing.size
            outgoing.forEach { edge ->
                out.writeLong(id); out.writeLong(edge.to); out.writeDouble(edge.distance)
                val type = "residential".toByteArray(); out.writeShort(type.size); out.write(type); out.writeByte(0)
                if (edge.grade.isNaN()) out.writeByte(0) else { out.writeByte(1); out.writeDouble(edge.grade) }
                targets += indexes.getValue(edge.to)
            }
        } }
        val file = File.createTempFile("cross-chain", ".graph").apply { deleteOnExit() }
        val channel = RandomAccessFile(file, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(
            FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java, DoubleArray::class.java,
            DoubleArray::class.java, LongArray::class.java, IntArray::class.java, IntArray::class.java,
            IntArray::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return ctor.newInstance(channel, ByteBuffer.wrap(bytes.toByteArray()), ids,
            DoubleArray(ids.size) { coords.getValue(ids[it]).first }, DoubleArray(ids.size) { coords.getValue(ids[it]).second },
            starts, first, counts, targets.toIntArray(), targets.size) as LazyMappedRoadGraph
    }

    @Test
    fun threeBandsMatchCombinedGraphForAllPreferences() {
        graph(longArrayOf(10, 20, 30, 40, 50, 60)).use { combined ->
            for (preference in RoutePreference.entries) {
                val a = graph(longArrayOf(10, 20, 30), mapOf(10L to edges.getValue(10), 20L to emptyList(), 30L to emptyList()))
                val b = graph(longArrayOf(20, 30, 40, 50), mapOf(20L to edges.getValue(20), 30L to edges.getValue(30), 40L to emptyList(), 50L to emptyList()))
                val c = graph(longArrayOf(40, 50, 60), mapOf(40L to edges.getValue(40), 50L to edges.getValue(50)))
                val actual = routeAcrossChain(listOf(ChainGraph({ a }, borrowed = true), ChainGraph({ b }), ChainGraph({ c })), 0, 2, preference)
                val expected = combined.route(0, 5, preference = preference)
                assertTrue(expected.isReachable); assertTrue(actual.isReachable)
                assertEquals(expected.totalCost, actual.totalCost, 1e-6, "$preference cost")
                assertEquals(expected.totalDistanceMeters, actual.totalDistanceMeters, 1e-6, "$preference distance")
                assertEquals(expected.nodeIds, actual.nodeIds, "$preference nodes")
                assertEquals(listOf(20L, 40L), actual.portalIds)
                assertTrue(actual.parts.all { it.nodeIds.isNotEmpty() })
                assertEquals(3, actual.stageSearchMs.size)
                a.close()
            }
        }
    }

    @Test
    fun missingPortalsAndCancellationAreReported() {
        val a = graph(longArrayOf(10, 20), mapOf(10L to listOf(Edge(20, 1.0))))
        val b = graph(longArrayOf(30, 40), emptyMap())
        val c = graph(longArrayOf(50, 60), emptyMap())
        val noPortal = routeAcrossChain(listOf(ChainGraph({ a }, borrowed = true), ChainGraph({ b }), ChainGraph({ c })), 0, 0)
        assertFalse(noPortal.isReachable)
        a.close()

        graph(longArrayOf(10, 20), mapOf(10L to listOf(Edge(20, 1.0)))).use { ga ->
            graph(longArrayOf(20, 30), mapOf(20L to listOf(Edge(30, 1.0)))).use { gb ->
                graph(longArrayOf(30, 40), emptyMap()).use { gc ->
                    val cancelled = routeAcrossChain(listOf(ChainGraph({ ga }, borrowed = true), ChainGraph({ gb }, borrowed = true), ChainGraph({ gc }, borrowed = true)), 0, 1, isCancelled = { true })
                    assertTrue(cancelled.wasCancelled)
                }
            }
        }
    }

    @Test
    fun borrowedGraphIsNotClosedAndTemporaryGraphsAreClosedOnFailure() {
        val borrowed = graph(longArrayOf(10, 20), mapOf(10L to listOf(Edge(20, 1.0))))
        val temp1 = graph(longArrayOf(20, 30), mapOf(20L to listOf(Edge(30, 1.0))))
        val temp2 = graph(longArrayOf(30, 40), emptyMap())
        var borrowedCloses = 0; var tempCloses = 0
        val result = routeAcrossChain(listOf(
            ChainGraph({ borrowed }, { borrowedCloses++ }, borrowed = true),
            ChainGraph({ temp1 }, { tempCloses++ }),
            ChainGraph({ temp2 }, { tempCloses++ }),
        ), 0, 1)
        assertFalse(result.isReachable)
        assertEquals(0, borrowedCloses)
        assertEquals(4, tempCloses) // each temporary open, including the final goal search, is paired with close
        borrowed.close(); temp1.close(); temp2.close()
    }

    @Test
    fun alreadyOpenedTemporaryGraphClosesIfOpeningNextGraphThrows() {
        val temporary = graph(longArrayOf(10, 20), mapOf(10L to listOf(Edge(20, 1.0))))
        var closes = 0
        assertFailsWith<IllegalStateException> {
            routeAcrossChain(listOf(
                ChainGraph({ temporary }, { closes++ }),
                ChainGraph({ throw IllegalStateException("test open failure") }),
                ChainGraph({ error("unreachable") }),
            ), 0, 0)
        }
        assertEquals(1, closes)
        temporary.close()
    }
}
