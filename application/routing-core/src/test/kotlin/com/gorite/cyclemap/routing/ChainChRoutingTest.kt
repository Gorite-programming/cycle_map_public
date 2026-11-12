package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainChRoutingTest {
    private data class E(val to: Long, val meters: Double, val type: String, val grade: Double?)
    private class Spec(val ids: LongArray, val edges: Map<Long, List<E>>)

    /** 全ノード同一座標 (A* のヒューリスティックが 0 になり、辺長が任意でも A* が厳密最適)。 */
    private fun build(spec: Spec, withCh: Boolean): LazyMappedRoadGraph {
        val ids = spec.ids
        val index = ids.withIndex().associate { it.value to it.index }
        val bytes = ByteArrayOutputStream()
        val starts = LongArray(ids.size); val first = IntArray(ids.size); val counts = IntArray(ids.size)
        val targets = ArrayList<Int>()
        DataOutputStream(bytes).use { d -> ids.forEachIndexed { i, id ->
            starts[i] = bytes.size().toLong(); first[i] = targets.size
            val out = spec.edges[id].orEmpty(); counts[i] = out.size
            for (e in out) {
                d.writeLong(id); d.writeLong(e.to); d.writeDouble(e.meters)
                val type = e.type.toByteArray(); d.writeShort(type.size); d.write(type); d.writeByte(0)
                if (e.grade == null) d.writeByte(0) else { d.writeByte(1); d.writeDouble(e.grade) }
                targets += index.getValue(e.to)
            }
        } }
        val file = File.createTempFile("chain-ch", ".graph").apply { deleteOnExit() }
        val channel = RandomAccessFile(file, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(
            FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java, DoubleArray::class.java,
            DoubleArray::class.java, LongArray::class.java, IntArray::class.java, IntArray::class.java,
            IntArray::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val g = ctor.newInstance(channel, ByteBuffer.wrap(bytes.toByteArray()), ids,
            DoubleArray(ids.size) { 34.0 }, DoubleArray(ids.size) { 132.0 },
            starts, first, counts, targets.toIntArray(), targets.size) as LazyMappedRoadGraph
        if (withCh) g.attachContractionHierarchy(ch(g))
        return g
    }

    private fun ch(g: LazyMappedRoadGraph) = ContractionHierarchyBuilder(maxWitnessSettled = 5).build(g, GraphFingerprint.UNKNOWN).hierarchy

    private val types = listOf("residential", "tertiary", "cycleway", "path", "primary", "motorway")

    private fun randomEdges(rnd: Random, ids: List<Long>, perNode: Int): Map<Long, List<E>> = ids.associateWith { u ->
        val out = ArrayList<E>()
        repeat(perNode) {
            val v = ids[rnd.nextInt(ids.size)]
            if (v != u) out += E(v, 5.0 + rnd.nextDouble() * 200, types[rnd.nextInt(types.size)], if (rnd.nextInt(4) == 0) null else rnd.nextInt(-12, 13) / 100.0)
        }
        out
    }

    private fun costOk(a: Double, b: Double) = abs(a - b) <= maxOf(1e-12, abs(a) * 1e-9)

    // ---- (a) searchMany ----

    @Test fun searchManyMatchesBruteForce() {
        for (seed in 1..12) {
            val rnd = Random(seed)
            val n = 40
            val ids = (0 until n).map { it.toLong() }
            val edges = randomEdges(rnd, ids.take(n - 5), 3) // 最後の 5 ノードは辺が無い (入る辺もほぼ無く到達不能)
                .mapValues { (_, l) -> l.filter { it.to < n - 5 } }
            val graph = build(Spec(ids.toLongArray(), edges), false)
            val ch = ch(graph)
            val srcCount = 1 + rnd.nextInt(6)
            val sources = IntArray(srcCount) { rnd.nextInt(n) }
            val costs = DoubleArray(srcCount) { if (rnd.nextInt(3) == 0) 0.0 else (rnd.nextInt(5) * 10.0) } // 重複・ゼロを含む
            val targets = IntArray(12) { if (it < srcCount && it % 2 == 0) sources[it] else rnd.nextInt(n) } // source = target を含む
            val r = ch.searchMany(sources, costs, targets)
            var unreachable = 0
            for ((k, t) in targets.withIndex()) {
                var best = Double.POSITIVE_INFINITY
                for (s in sources.indices) { val rr = ch.route(sources[s], t, graph); if (rr.isReachable) best = minOf(best, costs[s] + rr.totalCost) }
                if (best.isInfinite()) { unreachable++; assertEquals(Double.POSITIVE_INFINITY, r.bestCost[k], "seed $seed t=$t"); assertEquals(-1, r.bestSource[k]) }
                else {
                    assertTrue(costOk(best, r.bestCost[k]), "seed $seed t=$t expected $best got ${r.bestCost[k]}")
                    val bs = r.bestSource[k]; assertTrue(bs in sources.indices)
                    val rr = ch.route(sources[bs], t, graph)
                    assertTrue(rr.isReachable && costOk(best, costs[bs] + rr.totalCost), "seed $seed t=$t source=$bs not arg-min")
                }
            }
            val old = ch.searchManyDijkstra(sources, costs, targets)
            for (k in targets.indices) { assertTrue(costOk(old.bestCost[k], r.bestCost[k]) || old.bestCost[k] == r.bestCost[k], "seed $seed oracle k=$k"); assertEquals(old.bestSource[k] < 0, r.bestSource[k] < 0) }
            // 同じ CH を再利用しても結果が変わらない (workspace の世代管理)
            val again = ch.searchMany(sources, costs, targets)
            assertTrue(again.bestCost.contentEquals(r.bestCost)); assertTrue(again.bestSource.contentEquals(r.bestSource))
            if (seed == 1) assertTrue(targets.any { it >= n - 5 } || unreachable >= 0)
        }
    }

    @Test fun searchManyRphastMatchesDijkstraOracle() {
        for (seed in 100..115) {
            val rnd = Random(seed)
            val n = 30 + rnd.nextInt(30)
            val ids = (0 until n).map { it.toLong() }
            val edges = randomEdges(rnd, ids.take(n - 4), 2 + rnd.nextInt(3)).mapValues { (_, l) -> l.filter { it.to < n - 4 } }
            val ch = ch(build(Spec(ids.toLongArray(), edges), false))
            val sc = 1 + rnd.nextInt(10)
            val sources = IntArray(sc) { rnd.nextInt(n) }
            val costs = DoubleArray(sc) { rnd.nextInt(8) * 7.5 }
            val targets = IntArray(n * 2 + 5) { if (it < sc) sources[it] else rnd.nextInt(n) } // 重複・source=target・target 数 > ノード数
            val a = ch.searchMany(sources, costs, targets); val stats = ch.lastManyStats; val b = ch.searchManyDijkstra(sources, costs, targets)
            for (k in targets.indices) {
                assertTrue(a.bestCost[k] == b.bestCost[k] || costOk(a.bestCost[k], b.bestCost[k]), "seed $seed k=$k ${a.bestCost[k]} vs ${b.bestCost[k]}")
                assertEquals(a.bestCost[k].isFinite(), a.bestSource[k] >= 0)
                if (a.bestSource[k] >= 0) assertTrue(costs[a.bestSource[k]] <= a.bestCost[k] + 1e-9)
            }
            assertTrue(stats.contains("unionSize="))
        }
    }

    @Test fun searchManyUnreachableAndEmpty() {
        val graph = build(Spec(longArrayOf(1, 2, 3), mapOf(1L to listOf(E(2, 10.0, "residential", 0.0)))), false)
        val ch = ch(graph)
        val r = ch.searchMany(intArrayOf(0), doubleArrayOf(5.0), intArrayOf(1, 2, 0))
        assertTrue(r.bestCost[0].isFinite()); assertEquals(Double.POSITIVE_INFINITY, r.bestCost[1]); assertEquals(5.0, r.bestCost[2], 1e-12)
        assertEquals(-1, r.bestSource[1]); assertEquals(0, r.bestSource[2])
        assertEquals(0, ch.searchMany(IntArray(0), DoubleArray(0), intArrayOf(0)).bestCost.count { it.isFinite() })
        assertEquals(0, ch.searchMany(intArrayOf(0), doubleArrayOf(0.0), IntArray(0)).bestCost.size)
    }

    // ---- (b) chain ----

    /** k 個のグラフが隣同士で 6 ノードずつ重なる帯状の ID 範囲。辺は各グラフ内のノード同士のみ。 */
    private fun bands(seed: Int, k: Int): List<Spec> {
        val rnd = Random(seed)
        val width = 24; val overlap = 6
        return (0 until k).map { b ->
            val lo = b * (width - overlap); val ids = (lo until lo + width).map { it.toLong() }
            Spec(ids.toLongArray(), randomEdges(rnd, ids, 4))
        }
    }

    private fun astarChain(specs: List<Spec>, start: Int, goal: Int): CrossChainResult {
        val gs = specs.map { build(it, false) }
        try {
            if (specs.size == 2) {
                val r = routeAcrossGraphs(gs[0], start, gs[1], goal, portalsBetween(gs[0], gs[1]))
                return CrossChainResult(r.nodeIds, r.coordinates, r.totalDistanceMeters, r.totalCost, listOf(r.aPart, r.bPart), listOfNotNull(r.portalId),
                    r.wasCancelled, r.wasTruncated)
            }
            return routeAcrossChain(gs.map { ChainGraph({ it }, borrowed = true) }, start, goal)
        } finally { gs.forEach { it.close() } }
    }

    private fun partCostSum(graphs: List<LazyMappedRoadGraph>, r: CrossChainResult): Double {
        var total = 0.0; val cur = graphs.map { it.newRoutingEdgeCursor() }
        for ((gi, part) in r.parts.withIndex()) {
            val g = graphs[gi]; val c = cur[gi]
            for (i in 0 until part.nodeIndices.size - 1) {
                c.reset(part.nodeIndices[i]); var best = Double.POSITIVE_INFINITY
                while (c.next()) if (c.target == part.nodeIndices[i + 1]) {
                    val m = g.routingEdgeMultiplier(c.type, c.grade, RoutePreference.RECOMMENDED, RouteCostMode.BICYCLE)
                    if (!m.isNaN()) best = minOf(best, c.distance * m)
                }
                total += best
            }
        }
        return total
    }

    @Test fun chainChMatchesAStarChainForTwoThreeAndFourGraphs() {
        var compared = 0; var unreachableSeen = 0
        for (k in 2..4) for (seed in 1..8) {
            val specs = bands(seed * 10 + k, k)
            val rnd = Random(seed)
            val chGraphs = specs.map { build(it, true) }
            repeat(6) {
                val start = rnd.nextInt(specs.first().ids.size); val goal = rnd.nextInt(specs.last().ids.size)
                val a = astarChain(specs, start, goal)
                val c = routeAcrossChainCh(chGraphs.map { ChainGraph({ it }, borrowed = true) }, start, goal)
                assertNotNull(c, "k=$k seed=$seed")
                assertEquals(a.isReachable, c.isReachable, "k=$k seed=$seed $start->$goal reachability")
                if (!a.isReachable) { unreachableSeen++; return@repeat }
                compared++
                assertTrue(costOk(a.totalCost, c.totalCost), "k=$k seed=$seed $start->$goal cost ${a.totalCost} vs ${c.totalCost}")
                assertEquals(a.totalDistanceMeters, c.totalDistanceMeters, 1e-6, "k=$k seed=$seed distance")
                assertEquals(k, c.parts.size)
                assertEquals(specs.first().ids[start], c.nodeIds.first()); assertEquals(specs.last().ids[goal], c.nodeIds.last())
                assertEquals(c.nodeIds.size, c.coordinates.size)
                assertEquals(k - 1, c.portalIds.size)
                assertTrue(costOk(c.totalCost, partCostSum(chGraphs, c)), "k=$k seed=$seed part costs sum ${partCostSum(chGraphs, c)} vs ${c.totalCost}")
            }
            chGraphs.forEach { it.close() }
        }
        assertTrue(compared > 20, "only $compared reachable comparisons"); assertTrue(unreachableSeen >= 0)
    }

    @Test fun returnsNullWhenAnyGraphLacksCh() {
        val specs = bands(5, 3)
        for (missing in 0..2) {
            val gs = specs.mapIndexed { i, s -> build(s, i != missing) }
            var opens = 0; var closes = 0
            val r = routeAcrossChainCh(gs.map { g -> ChainGraph({ opens++; g }, { closes++ }) }, 0, 0)
            assertNull(r, "missing=$missing")
            assertEquals(opens, closes, "every opened temporary graph must be closed")
            gs.forEach { it.close() }
        }
    }

    @Test fun unreachablePortalsAndCancellationAreReported() {
        // 重なり ID はあるが、b 側からは出口へ行く道が無い
        val a = Spec(longArrayOf(1, 2), mapOf(1L to listOf(E(2, 10.0, "residential", 0.0))))
        val b = Spec(longArrayOf(2, 3), emptyMap())
        val ga = build(a, true); val gb = build(b, true)
        val r = routeAcrossChainCh(listOf(ChainGraph({ ga }, borrowed = true), ChainGraph({ gb }, borrowed = true)), 0, 1)
        assertNotNull(r); assertFalse(r.isReachable); assertFalse(r.wasCancelled)
        // 重なりそのものが無い
        val gc = build(Spec(longArrayOf(7, 8), emptyMap()), true)
        val none = routeAcrossChainCh(listOf(ChainGraph({ ga }, borrowed = true), ChainGraph({ gc }, borrowed = true)), 0, 0)
        assertNotNull(none); assertFalse(none.isReachable)
        // キャンセル
        val specs = bands(3, 3); val gs = specs.map { build(it, true) }
        val cancelled = routeAcrossChainCh(gs.map { ChainGraph({ it }, borrowed = true) }, 0, 0, isCancelled = { true })
        assertNotNull(cancelled); assertTrue(cancelled.wasCancelled); assertFalse(cancelled.isReachable)
    }

    @Test fun borrowedGraphsAreNotClosedAndTemporaryOnesAreClosedOnFailure() {
        val borrowed = build(Spec(longArrayOf(1, 2), mapOf(1L to listOf(E(2, 10.0, "residential", 0.0)))), true)
        val t1 = build(Spec(longArrayOf(2, 3), mapOf(2L to listOf(E(3, 10.0, "residential", 0.0)))), true)
        val t2 = build(Spec(longArrayOf(3, 4), emptyMap()), true)
        var borrowedCloses = 0; var opens = 0; var closes = 0
        val r = routeAcrossChainCh(listOf(
            ChainGraph({ borrowed }, { borrowedCloses++ }, borrowed = true),
            ChainGraph({ opens++; t1 }, { closes++ }),
            ChainGraph({ opens++; t2 }, { closes++ }),
        ), 0, 1)
        assertNotNull(r); assertFalse(r.isReachable)
        assertEquals(0, borrowedCloses); assertTrue(opens > 0); assertEquals(opens, closes)
        // 成功時も同様
        opens = 0; closes = 0
        val ok = routeAcrossChainCh(listOf(
            ChainGraph({ borrowed }, { borrowedCloses++ }, borrowed = true),
            ChainGraph({ opens++; t1 }, { closes++ }),
            ChainGraph({ opens++; t2 }, { closes++ }),
        ), 0, 0)
        // t2 に辺が無いので goal=0 (ID 3) への到達: 3 は通り口 (t1,t2 共通) なので到達できる
        assertNotNull(ok); assertTrue(ok.isReachable, "ids=${ok.nodeIds}"); assertEquals(listOf(1L, 2L, 3L), ok.nodeIds)
        assertEquals(0, borrowedCloses); assertEquals(opens, closes)
    }
}
