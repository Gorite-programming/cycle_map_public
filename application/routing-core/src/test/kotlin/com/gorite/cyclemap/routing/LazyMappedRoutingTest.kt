package com.gorite.cyclemap.routing

import java.io.File
import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LazyMappedRoutingTest {
    @Test fun boundedNearestNeverReturnsNodesOutsideRadius() {
        // Exercise both the linear scan and spatial grid paths.
        for (count in listOf(3, 80)) {
            graphWith(DoubleArray(count) { 34.0 + it * 0.0001 }, DoubleArray(count) { 132.0 }).use { graph ->
                assertTrue(graph.nearestNodeCandidates(34.0, 133.0, 400.0).isEmpty())
                assertEquals(-1, graph.nearestNodeIndex(34.0, 133.0, 400.0))
                assertTrue(graph.nearestNodeIndex(34.0, 133.0) >= 0) // unrestricted API is unchanged
                val nearest = graph.nearestNodeIndex(34.00001, 132.0)
                val candidates = graph.nearestNodeCandidates(34.00001, 132.0, 20.0, count)
                assertEquals(nearest, candidates.first())
                assertEquals(nearest, graph.nearestNodeIndex(34.00001, 132.0, 20.0))
                assertTrue(candidates.all {
                    val node = graph.nodeAt(it)
                    haversineMeters(34.00001, 132.0, node.latitude, node.longitude) <= 20.0
                })
                assertTrue(graph.nearestNodeCandidates(34.0, 132.0, 400.0, 0).isEmpty())
                assertTrue(graph.nearestNodeCandidates(34.00001, 132.0, 0.0).isEmpty())
                assertEquals(0, graph.nearestNodeIndex(34.0, 132.0, 0.0))
            }
        }
    }

    private data class FixtureEdge(val target: Int, val distanceMeters: Double, val gradePercent: Double)

    private fun graphWith(lats: DoubleArray, lons: DoubleArray): LazyMappedRoadGraph {
        val n = lats.size
        val tmp = File.createTempFile("lazymapped-test", ".graph")
        tmp.deleteOnExit()
        val channel = RandomAccessFile(tmp, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(
            FileChannel::class.java,
            ByteBuffer::class.java,
            LongArray::class.java,
            DoubleArray::class.java,
            DoubleArray::class.java,
            LongArray::class.java,
            IntArray::class.java,
            IntArray::class.java,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
        )
        ctor.isAccessible = true
        val graph = ctor.newInstance(
            channel,
            ByteBuffer.allocate(0),
            LongArray(n) { it.toLong() },
            lats,
            lons,
            LongArray(n),
            IntArray(n),
            IntArray(n),
            IntArray(0),
            0,
        ) as LazyMappedRoadGraph
        return graph
    }

    private fun graphWithAlternativeGrades(): LazyMappedRoadGraph = graphWithEdges(
        listOf(
            listOf(
                FixtureEdge(1, 115.0, 0.0),
                FixtureEdge(2, 105.0, 2.0),
                FixtureEdge(3, 90.0, 10.0),
            ),
            listOf(FixtureEdge(4, 115.0, 0.0)),
            listOf(FixtureEdge(4, 105.0, 2.0)),
            listOf(FixtureEdge(4, 90.0, 10.0)),
            emptyList(),
        ),
    )

    private fun graphWithEdges(
        edges: List<List<FixtureEdge>>,
        lats: DoubleArray = DoubleArray(edges.size) { 34.0 },
    ): LazyMappedRoadGraph {
        val bytes = ByteArrayOutputStream()
        val edgeStarts = LongArray(edges.size)
        val edgeFirstOrdinals = IntArray(edges.size)
        val edgeCounts = IntArray(edges.size)
        val edgeTargets = ArrayList<Int>()
        DataOutputStream(bytes).use { out ->
            edges.forEachIndexed { source, outgoing ->
                edgeStarts[source] = bytes.size().toLong()
                edgeFirstOrdinals[source] = edgeTargets.size
                edgeCounts[source] = outgoing.size
                outgoing.forEach { edge ->
                    out.writeLong(100L + source)
                    out.writeLong(100L + edge.target)
                    out.writeDouble(edge.distanceMeters)
                    val roadType = "residential".toByteArray(Charsets.UTF_8)
                    out.writeShort(roadType.size)
                    out.write(roadType)
                    out.writeByte(0) // not one-way metadata
                    out.writeByte(1) // grade is present
                    out.writeDouble(edge.gradePercent)
                    edgeTargets += edge.target
                }
            }
        }
        val tmp = File.createTempFile("lazymapped-edges", ".graph").apply { deleteOnExit() }
        val channel = RandomAccessFile(tmp, "r").channel
        val nodeIds = LongArray(edges.size) { 100L + it }
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(
            FileChannel::class.java,
            ByteBuffer::class.java,
            LongArray::class.java,
            DoubleArray::class.java,
            DoubleArray::class.java,
            LongArray::class.java,
            IntArray::class.java,
            IntArray::class.java,
            IntArray::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        return ctor.newInstance(
            channel,
            ByteBuffer.wrap(bytes.toByteArray()),
            nodeIds,
            lats,
            DoubleArray(edges.size) { 131.0 },
            edgeStarts,
            edgeFirstOrdinals,
            edgeCounts,
            edgeTargets.toIntArray(),
            edgeTargets.size,
        ) as LazyMappedRoadGraph
    }

    private fun bruteForce(lats: DoubleArray, lons: DoubleArray, lat: Double, lon: Double): Int {
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in lats.indices) {
            val d = haversineMeters(lat, lon, lats[i], lons[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    @Test
    fun nearestNodeIndexMatchesBruteForce() {
        val random = Random(42)
        val n = 300
        val lats = DoubleArray(n) { 33.7 + random.nextDouble() * 1.1 }
        val lons = DoubleArray(n) { 130.7 + random.nextDouble() * 1.5 }
        graphWith(lats, lons).use { graph ->
            repeat(50) {
                val qLat = 33.7 + random.nextDouble() * 1.1
                val qLon = 130.7 + random.nextDouble() * 1.5
                assertEquals(bruteForce(lats, lons, qLat, qLon), graph.nearestNodeIndex(qLat, qLon))
            }
        }
    }

    @Test
    fun searchBoundsContainEndpoints() {
        val bounds = LazyMappedRoadGraph.searchBoundsFor(34.0, 131.0, 34.5, 131.5)
        assertTrue(bounds.contains(34.0, 131.0))
        assertTrue(bounds.contains(34.5, 131.5))
        assertTrue(bounds.contains(34.25, 131.25))
        assertTrue(bounds.maxLatitude - bounds.minLatitude > 0.5)
    }

    @Test
    fun sameStartAndGoalReturnsSingleNode() {
        graphWith(doubleArrayOf(34.0), doubleArrayOf(131.0)).use { graph ->
            val result = graph.route(0, 0)
            assertTrue(result.isReachable)
            assertEquals(listOf(0L), result.nodeIds)
        }
    }

    @Test
    fun maxExpandedNodesMustBePositive() {
        graphWith(doubleArrayOf(34.0, 34.1), doubleArrayOf(131.0, 131.1)).use { graph ->
            assertFailsWith<IllegalArgumentException> {
                graph.route(0, 1, maxExpandedNodes = 0)
            }
        }
    }

    @Test
    fun gradePenalty_asymmetricAndBounded() {
        val method = LazyMappedRoadGraph::class.java.getDeclaredMethod(
            "gradePenalty",
            Float::class.javaPrimitiveType,
            RoutePreference::class.java,
        )
        method.isAccessible = true

        graphWith(doubleArrayOf(34.0), doubleArrayOf(131.0)).use { graph ->
            fun penalty(grade: Float, pref: RoutePreference): Double =
                method.invoke(graph, grade, pref) as Double

            // NaN -> 1.0
            assertEquals(1.0, penalty(Float.NaN, RoutePreference.RECOMMENDED), 1e-6)

            // RECOMMENDED:
            val flat = penalty(0.0f, RoutePreference.RECOMMENDED)
            val gentleDown = penalty(-3.0f, RoutePreference.RECOMMENDED)
            val steepDown = penalty(-10.0f, RoutePreference.RECOMMENDED)
            val up = penalty(5.0f, RoutePreference.RECOMMENDED)

            assertEquals(1.0, flat, 1e-6)
            assertTrue(gentleDown < flat)
            assertTrue(gentleDown >= 0.95)
            assertTrue(steepDown > gentleDown)
            assertTrue(up > flat)

            // FLAT: 上り坂回避は上限8.0倍でクランプ。おすすめより常に強く、勾配が急なほど増える
            val flatUp = penalty(30.0f, RoutePreference.FLAT)
            assertEquals(8.0, flatUp, 1e-9)
            for (g in listOf(1.0f, 3.0f, 5.0f, 10.0f)) {
                assertTrue(penalty(g, RoutePreference.FLAT) > penalty(g, RoutePreference.RECOMMENDED), "grade $g")
            }
            assertTrue(penalty(10.0f, RoutePreference.FLAT) > penalty(5.0f, RoutePreference.FLAT))
            assertEquals(2.5, penalty(5.0f, RoutePreference.FLAT), 1e-9) // 1 + 0.20*5 + 0.02*25
        }
    }

    @Test
    fun routePreference_selectsFlatRecommendedAndSteepAlternativesWhenAvailable() {
        graphWithAlternativeGrades().use { graph ->
            val flat = graph.route(0, 4, preference = RoutePreference.FLAT)
            val recommended = graph.route(0, 4, preference = RoutePreference.RECOMMENDED)
            val hill = graph.route(0, 4, preference = RoutePreference.HILL_CLIMB)

            assertEquals(listOf(100L, 101L, 104L), flat.nodeIds)
            assertEquals(listOf(100L, 102L, 104L), recommended.nodeIds)
            assertEquals(listOf(100L, 103L, 104L), hill.nodeIds)
        }
    }

    @Test
    fun nearestNodeIndexLargeGridMatchesBruteForce() {
        val random = Random(2026)
        val n = 5_000
        val lats = DoubleArray(n) { 33.8 + random.nextDouble() * 1.0 }
        val lons = DoubleArray(n) { 130.8 + random.nextDouble() * 1.4 }
        graphWith(lats, lons).use { graph ->
            repeat(150) {
                val qLat = 33.7 + random.nextDouble() * 1.2
                val qLon = 130.7 + random.nextDouble() * 1.6
                assertEquals(bruteForce(lats, lons, qLat, qLon), graph.nearestNodeIndex(qLat, qLon))
            }
        }
    }

    @Test
    fun costModeMultipliersPreserveAdmissibility() {
        val method = LazyMappedRoadGraph::class.java.getDeclaredMethod(
            "multiplier",
            Int::class.javaPrimitiveType,
            RouteCostMode::class.java,
        )
        method.isAccessible = true
        graphWith(doubleArrayOf(34.0), doubleArrayOf(131.0)).use { graph ->
            for (mode in RouteCostMode.entries) {
                for (typeCode in 0..10) {
                    val m = method.invoke(graph, typeCode, mode) as Double?
                    if (m != null) {
                        assertTrue(m >= 0.90, "Mode $mode type $typeCode multiplier $m must be >= 0.90")
                    }
                }
            }
        }
    }

    @Test
    fun emptyGraphNearestNodeAndRouteFailCleanly() {
        graphWith(DoubleArray(0), DoubleArray(0)).use { graph ->
            assertEquals(0, graph.nodeCount)
            assertEquals(-1, graph.nearestNodeIndex(34.0, 132.0))
            assertEquals(-1, graph.nearestNodeIndex(34.0, 132.0, 400.0))
            assertTrue(graph.nearestNodeCandidates(34.0, 132.0).isEmpty())
            assertFailsWith<IllegalStateException> { graph.nearestNode(34.0, 132.0) }
            assertFailsWith<IllegalArgumentException> { graph.route(0, 0) }
        }

        val (graphFile, indexFile) = writeGraphAndIndex(nodeCount = 0)
        LazyMappedRoadGraph.load(graphFile, indexFile).use { loaded ->
            assertEquals(0, loaded.nodeCount)
            assertEquals(0, loaded.edgeCount)
            assertEquals(-1, loaded.nearestNodeIndex(34.0, 132.0))
            assertFailsWith<IllegalStateException> { loaded.nearestNode(34.0, 132.0) }
        }
        assertEquals(0, LazyMappedRoadGraph.loadSortedNodeIdIndex(graphFile).size)
    }

    @Test
    fun corruptedOrEmptyGraphAndIndexRejectWithIllegalArgumentException() {
        val (validGraph, validIndex) = writeGraphAndIndex(nodeCount = 1)

        // 空の .graph / .idx
        val emptyGraph = File.createTempFile("empty", ".graph").apply { deleteOnExit() }
        val emptyIndex = File.createTempFile("empty", ".idx").apply { deleteOnExit() }
        assertFailsWith<IllegalArgumentException> { LazyMappedRoadGraph.load(emptyGraph, validIndex) }
        assertFailsWith<IllegalArgumentException> { LazyMappedRoadGraph.loadSortedNodeIdIndex(emptyGraph) }
        assertFailsWith<IllegalArgumentException> { LazyMappedRoadGraph.load(validGraph, emptyIndex) }

        // 負の nodeCount
        val negativeNodeGraph = File.createTempFile("neg-nodes", ".graph").apply { deleteOnExit() }
        DataOutputStream(negativeNodeGraph.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1")
            out.writeInt(-1)
        }
        assertFailsWith<IllegalArgumentException> { LazyMappedRoadGraph.load(negativeNodeGraph, validIndex) }
        assertFailsWith<IllegalArgumentException> { LazyMappedRoadGraph.loadSortedNodeIdIndex(negativeNodeGraph) }

        // 文字列テーブルの件数が残りバイト数を超える .idx
        val badStringTableIndex = File.createTempFile("bad-strtable", ".idx").apply { deleteOnExit() }
        DataOutputStream(badStringTableIndex.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2")
            out.writeInt(1)
            out.writeLong(0L)
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(0) // edgeCount
            out.writeUTF("CYCLEMAP_ROADNAME_V1")
            out.writeInt(1_000_000) // count exceeds remaining bytes
        }
        assertFailsWith<IllegalArgumentException> { LazyMappedRoadGraph.load(validGraph, badStringTableIndex) }
    }

    @Test
    fun routeHonorsSmallMaxExpandedNodes() {
        val nodeCount = 6
        val edges = List(nodeCount) { i ->
            if (i + 1 < nodeCount) listOf(FixtureEdge(i + 1, 100.0, 0.0)) else emptyList()
        }
        graphWithEdges(edges, DoubleArray(nodeCount) { 34.0 + it * 0.001 }).use { graph ->
            val truncated1 = graph.route(0, 5, maxExpandedNodes = 1)
            assertFalse(truncated1.isReachable)
            assertTrue(truncated1.wasTruncated)
            assertEquals(1, graph.lastExpandedNodes)

            val truncated3 = graph.route(0, 5, maxExpandedNodes = 3)
            assertFalse(truncated3.isReachable)
            assertTrue(truncated3.wasTruncated)
            assertEquals(3, graph.lastExpandedNodes)

            val reachable3 = graph.route(0, 2, maxExpandedNodes = 3)
            assertTrue(reachable3.isReachable)
            assertFalse(reachable3.wasTruncated)
            assertEquals(listOf(100L, 101L, 102L), reachable3.nodeIds)
            assertEquals(3, graph.lastExpandedNodes)
        }
    }

    private fun writeGraphAndIndex(nodeCount: Int): Pair<File, File> {
        val graphFile = File.createTempFile("fixture", ".graph").apply { deleteOnExit() }
        DataOutputStream(graphFile.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1")
            out.writeInt(nodeCount)
            repeat(nodeCount) { i ->
                out.writeLong(100L + i)
                out.writeDouble(34.0 + i * 0.001)
                out.writeDouble(132.0 + i * 0.001)
            }
            out.writeInt(0) // edgeCount
        }
        val indexFile = File(graphFile.path + ".idx").apply { deleteOnExit() }
        DataOutputStream(indexFile.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2")
            out.writeInt(nodeCount)
            repeat(nodeCount) { out.writeLong(0L) }
            repeat(nodeCount) { out.writeInt(0) }
            repeat(nodeCount) { out.writeInt(0) }
            out.writeInt(0) // edgeCount
        }
        return graphFile to indexFile
    }
}
