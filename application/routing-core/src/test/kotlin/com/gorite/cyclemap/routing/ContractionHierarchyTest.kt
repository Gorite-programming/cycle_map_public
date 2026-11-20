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
    @Test fun addedRoadTypesUseSharedCostsAndCHMatchesAStar() {
        val types = listOf("cycleway", "trunk", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
            "living_street", "footway", "pedestrian", "road")
        val walk = listOf(0.90, 1.30, 1.30, 1.30, 1.20, 1.10, 0.95, 0.90, 0.90, 1.00)
        val car = listOf(null, 0.90, 0.95, 0.95, 1.00, 1.10, 1.35, null, null, 1.20)
        for ((i, type) in types.withIndex()) {
            graphFrom(listOf(listOf(E(1, 100.0, type)), emptyList())).use { graph ->
                val a = graph.route(0, 1)
                val ch = ContractionHierarchyBuilder().build(graph, GraphFingerprint.UNKNOWN).hierarchy.route(0, 1, graph)
                val expected = CyclingCostModel.cost(GraphEdge(0, 1, 100.0, type, gradePercent = 0.0))!!
                assertEquals(expected, a.totalCost, 1e-9, type)
                assertEquals(expected, ch.totalCost, 1e-9, type)
                assertEquals(a.nodeIndices, ch.nodeIndices.toList(), type)
                assertEquals(100.0 * walk[i], graph.route(0, 1, costMode = RouteCostMode.WALK).totalCost, 1e-9, type)
                val driving = graph.route(0, 1, costMode = RouteCostMode.CAR)
                assertEquals(car[i] != null, driving.isReachable, type)
                car[i]?.let { assertEquals(100.0 * it, driving.totalCost, 1e-9, type) }
            }
        }
        // The new cycleway cost must beat a shorter residential alternative.
        graphFrom(listOf(listOf(E(1, 120.0, "cycleway"), E(2, 100.0, "residential")), emptyList(), listOf(E(1, 1.0)))).use { graph ->
            val a = graph.route(0, 1)
            val ch = ContractionHierarchyBuilder().build(graph, GraphFingerprint.UNKNOWN).hierarchy.route(0, 1, graph)
            assertEquals(listOf(0, 1), a.nodeIndices)
            assertEquals(a.nodeIndices, ch.nodeIndices.toList())
            assertEquals(a.totalCost, ch.totalCost, 1e-9)
        }
        // A trunk in the middle of a route must survive arterial-only filtering.
        graphFrom(listOf(listOf(E(1, 10.0)), listOf(E(2, 10.0, "trunk")), listOf(E(3, 10.0)), emptyList())).use { graph ->
            assertTrue(graph.route(0, 3, arterialOnly = true).isReachable)
            assertEquals(42.0, graph.route(0, 3, arterialPenaltyMultiplier = 2.0).totalCost, 1e-9)
        }
    }

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

    @Test fun mmapOpenMatchesHeapReadForEveryPair() {
        fixture().use { graph ->
            val chFile = File.createTempFile("ch-test", ".graph.ch").apply { deleteOnExit() }
            ContractionHierarchyBuilder(maxWitnessSettled = 2).build(graph, GraphFingerprint.UNKNOWN).hierarchy.write(chFile)
            val heap = ContractionHierarchy.read(chFile, GraphFingerprint.UNKNOWN)
            ContractionHierarchy.open(chFile, GraphFingerprint.UNKNOWN).use { mapped ->
                assertEquals(heap.nodeCount, mapped.nodeCount); assertEquals(heap.edgeCount, mapped.edgeCount)
                for (s in 0 until graph.nodeCount) for (g in 0 until graph.nodeCount) {
                    val h = heap.route(s, g, graph); val m = mapped.route(s, g, graph)
                    assertEquals(h.isReachable, m.isReachable, "$s->$g")
                    assertTrue(h.nodeIndices.contentEquals(m.nodeIndices), "$s->$g ${h.nodeIndices.contentToString()} vs ${m.nodeIndices.contentToString()}")
                    assertEquals(h.totalCost, m.totalCost); assertEquals(h.totalDistance, m.totalDistance)
                }
                // A mapped instance can be re-serialized byte for byte.
                val again = File.createTempFile("ch-test2", ".graph.ch").apply { deleteOnExit() }
                mapped.write(again); assertTrue(chFile.readBytes().contentEquals(again.readBytes()))
            }
        }
    }

    @Test fun graphIdentityMismatchRejectsOpen() {
        fixture().use { graph ->
            val chFile = File.createTempFile("ch-test", ".graph.ch").apply { deleteOnExit() }
            val sourceGraph = File.createTempFile("ch-source", ".graph").apply { writeText("source graph A"); deleteOnExit() }
            val otherGraph = File.createTempFile("ch-other", ".graph").apply { writeText("source graph B"); deleteOnExit() }
            ContractionHierarchyBuilder(maxWitnessSettled = 2).build(graph, GraphFingerprint.fromFile(sourceGraph)).hierarchy.write(chFile)
            ContractionHierarchy.open(chFile, sourceGraph).close()
            kotlin.test.assertFailsWith<IllegalArgumentException> { ContractionHierarchy.open(chFile, otherGraph) }
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

    /**
     * 平行な辺が多く、近道が最大に増える設定 (witness=1) の密なグラフでも、前処理が短時間で終わり、A* と同じ結果になること。
     * 注意: 茨城・神奈川で起きた爆発 (最後に残る少数のノードに平行な辺が何十万本も集まる) は、数百万ノードの実データでしか再現できず、
     * この小さなグラフでは、整理前の実装でも終わる (確かめた)。再発の検知は、実データでの前処理時間 (茨城 約 2 分) で行う。
     */
    @Test fun denseGraphWithManyParallelEdgesFinishesAndMatchesAStar() {
        val n = 36
        val out = List(n) { u ->
            val edges = ArrayList<E>()
            for (v in 0 until n) if (v != u) for (k in 0 until 4) edges += E(v, 5.0 + ((u * 31 + v * 17 + k * 7) % 53))
            edges
        }
        graphFrom(out).use { graph ->
            var built: ContractionHierarchy? = null; var failure: Throwable? = null
            val worker = Thread { try { built = ContractionHierarchyBuilder(maxWitnessSettled = 1).build(graph, GraphFingerprint.UNKNOWN).hierarchy } catch (t: Throwable) { failure = t } }
            worker.isDaemon = true; worker.start(); worker.join(30_000)
            assertFalse(worker.isAlive, "前処理が 30 秒で終わらない (平行な辺が積み重なって爆発している疑い)")
            failure?.let { throw it }
            val ch = built!!
            for (s in 0 until n step 5) for (g in 0 until n) {
                val a = graph.route(s, g, preference = RoutePreference.RECOMMENDED, costMode = RouteCostMode.BICYCLE)
                val c = ch.route(s, g, graph)
                assertEquals(a.isReachable, c.isReachable, "$s->$g reachability")
                if (a.isReachable) assertTrue(abs(a.totalCost - c.totalCost) <= maxOf(1e-12, abs(a.totalCost) * 1e-9), "$s->$g cost ${a.totalCost} != ${c.totalCost}")
            }
        }
    }

    /** 進捗の表示は結果を変えない (表示あり・なしで、近道の数・辺の数が同じ)。 */
    @Test fun progressCallbackDoesNotChangeTheResult() {
        val n = 24
        val out = List(n) { u -> (1..5).mapNotNull { step -> val v = (u * 7 + step * 11 + 3) % n; if (v != u) E(v, 4.0 + ((u * 19 + v * 13 + step) % 37)) else null } }
        graphFrom(out).use { graph ->
            val plain = ContractionHierarchyBuilder(maxWitnessSettled = 50).build(graph, GraphFingerprint.UNKNOWN)
            var calls = 0
            val withProgress = ContractionHierarchyBuilder(maxWitnessSettled = 50).build(graph, GraphFingerprint.UNKNOWN) { calls++ }
            assertEquals(plain.shortcutCount, withProgress.shortcutCount)
            assertEquals(plain.hierarchy.edgeCount, withProgress.hierarchy.edgeCount)
            assertTrue(calls >= 0)
        }
    }

    private fun linearChainCh(n: Int, upward: Boolean): Pair<LazyMappedRoadGraph, ContractionHierarchy> {
        val out = List(n) { u -> if (u + 1 < n) listOf(E(u + 1, 10.0)) else emptyList() }
        val graph = graphFrom(out)
        val edgeN = n - 1
        val ranks = IntArray(n) { if (upward) it else n - 1 - it }
        val from = IntArray(edgeN) { it }
        val to = IntArray(edgeN) { it + 1 }
        val cost = DoubleArray(edgeN) { 10.0 }
        val dist = DoubleArray(edgeN) { 10.0 }
        val minusOne = IntArray(edgeN) { -1 }
        val ch = ContractionHierarchy.create(n, edgeN, ranks, from, to, cost, dist, minusOne, minusOne, minusOne, GraphFingerprint.UNKNOWN)
        return graph to ch
    }

    @Test fun routeCancellationStopsAndNonCancelledMatchesBaseline() {
        fixture().use { graph ->
            val ch = ContractionHierarchyBuilder(maxWitnessSettled = 2).build(graph, GraphFingerprint.UNKNOWN).hierarchy
            for (s in 0 until graph.nodeCount) for (g in 0 until graph.nodeCount) {
                val base = ch.route(s, g, graph)
                val withFalse = ch.route(s, g, graph) { false }
                assertFalse(base.wasCancelled)
                assertFalse(withFalse.wasCancelled)
                assertEquals(base.isReachable, withFalse.isReachable, "$s->$g")
                assertTrue(base.nodeIndices.contentEquals(withFalse.nodeIndices), "$s->$g")
                assertEquals(base.totalCost, withFalse.totalCost)
                assertEquals(base.totalDistance, withFalse.totalDistance)
                if (s != g) {
                    val cancelled = ch.route(s, g, graph) { true }
                    assertTrue(cancelled.wasCancelled, "$s->$g should be cancelled")
                    assertFalse(cancelled.isReachable, "$s->$g cancelled must not be reachable")
                    assertTrue(cancelled.nodeIndices.isEmpty())
                    assertTrue(cancelled.totalCost.isInfinite())
                }
            }
        }
        // 1024 ステップ境界での途中キャンセル (2500 ノード上向き鎖)
        val n = 2500
        val (chainGraph, ch) = linearChainCh(n, upward = true)
        chainGraph.use { graph ->
            val baseline = ch.route(0, n - 1, graph)
            val withFalse = ch.route(0, n - 1, graph) { false }
            assertTrue(baseline.isReachable)
            assertFalse(baseline.wasCancelled)
            assertTrue(baseline.nodeIndices.contentEquals(withFalse.nodeIndices))
            assertEquals(baseline.totalCost, withFalse.totalCost)
            assertEquals(baseline.totalDistance, withFalse.totalDistance)

            var checks = 0
            val midCancelled = ch.route(0, n - 1, graph) { ++checks >= 2 }
            assertEquals(2, checks, "route() should cancel at first 1024-step check inside loop")
            assertTrue(midCancelled.wasCancelled)
            assertFalse(midCancelled.isReachable)
            assertTrue(midCancelled.nodeIndices.isEmpty())

            // キャンセル後も同一 CH インスタンスの次回クエリが正しく動くこと
            val afterCancel = ch.route(0, n - 1, graph)
            assertTrue(afterCancel.isReachable)
            assertTrue(baseline.nodeIndices.contentEquals(afterCancel.nodeIndices))
            assertEquals(baseline.totalCost, afterCancel.totalCost)
        }
    }

    @Test fun searchManyCancellationStopsInAllPhasesAndNonCancelledMatchesBaseline() {
        val n = 2500
        // (1) 前向き探索フェーズでのキャンセル
        val (upGraph, upCh) = linearChainCh(n, upward = true)
        upGraph.use {
            val sources = intArrayOf(0); val costs = doubleArrayOf(0.0); val targets = intArrayOf(n - 1)
            val base = upCh.searchMany(sources, costs, targets)
            val withFalse = upCh.searchMany(sources, costs, targets) { false }
            assertFalse(base.wasCancelled); assertFalse(withFalse.wasCancelled)
            assertTrue(base.bestCost.contentEquals(withFalse.bestCost))
            assertTrue(base.bestSource.contentEquals(withFalse.bestSource))

            val immediate = upCh.searchMany(sources, costs, targets) { true }
            assertTrue(immediate.wasCancelled)
            assertTrue(immediate.bestCost[0].isInfinite())
            assertEquals(-1, immediate.bestSource[0])

            var fwdChecks = 0
            val fwdCancelled = upCh.searchMany(sources, costs, targets) { ++fwdChecks >= 2 }
            assertEquals(2, fwdChecks, "searchMany() should cancel at 1024-step check in forward loop")
            assertTrue(fwdCancelled.wasCancelled)
            assertTrue(fwdCancelled.bestCost[0].isInfinite())
            assertEquals(-1, fwdCancelled.bestSource[0])

            val afterCancel = upCh.searchMany(sources, costs, targets)
            assertFalse(afterCancel.wasCancelled)
            assertEquals(base.bestCost[0], afterCancel.bestCost[0])
            assertEquals(base.bestSource[0], afterCancel.bestSource[0])
        }

        // (2) RPHAST 閉包収集・下向きスウィープフェーズでのキャンセル
        val (downGraph, downCh) = linearChainCh(n, upward = false)
        downGraph.use {
            val sources = intArrayOf(0); val costs = doubleArrayOf(0.0); val targets = intArrayOf(n - 1)
            val base = downCh.searchMany(sources, costs, targets)
            val withFalse = downCh.searchMany(sources, costs, targets) { false }
            assertTrue(base.bestCost.contentEquals(withFalse.bestCost))
            assertTrue(base.bestSource.contentEquals(withFalse.bestSource))

            var closureChecks = 0
            val closureCancelled = downCh.searchMany(sources, costs, targets) { ++closureChecks >= 2 }
            assertEquals(2, closureChecks, "searchMany() should cancel at 1024-step check in RPHAST closure loop")
            assertTrue(closureCancelled.wasCancelled)
            assertTrue(closureCancelled.bestCost[0].isInfinite())
            assertEquals(-1, closureCancelled.bestSource[0])

            // 開始前(1) + 閉包2500ノード(1024, 2048 で 2, 3) + 下向きスウィープ(1024 で 4)
            var sweepChecks = 0
            val sweepCancelled = downCh.searchMany(sources, costs, targets) { ++sweepChecks >= 4 }
            assertEquals(4, sweepChecks, "searchMany() should cancel at 1024-step check in RPHAST downward sweep")
            assertTrue(sweepCancelled.wasCancelled)
            assertTrue(sweepCancelled.bestCost[0].isInfinite())
            assertEquals(-1, sweepCancelled.bestSource[0])

            val afterCancel = downCh.searchMany(sources, costs, targets)
            assertFalse(afterCancel.wasCancelled)
            assertEquals(base.bestCost[0], afterCancel.bestCost[0])
            assertEquals(base.bestSource[0], afterCancel.bestSource[0])
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
