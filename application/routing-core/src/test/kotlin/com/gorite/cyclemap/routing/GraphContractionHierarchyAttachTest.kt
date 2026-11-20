package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphContractionHierarchyAttachTest {
    private data class E(val to: Int, val meters: Double, val type: String = "residential", val grade: Double? = 0.0)

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
        val tmp = File.createTempFile("ch-attach", ".graph"); tmp.deleteOnExit(); val channel = RandomAccessFile(tmp, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java, DoubleArray::class.java, DoubleArray::class.java, LongArray::class.java, IntArray::class.java, IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        return ctor.newInstance(channel, ByteBuffer.wrap(bytes.toByteArray()), LongArray(out.size) { it.toLong() + 1000 }, DoubleArray(out.size) { 34.0 }, DoubleArray(out.size) { 132.0 } /* 同一座標: 辺長が任意なので A* のヒューリスティックを 0 にする */, starts, first, counts, targets.toIntArray(), targets.size) as LazyMappedRoadGraph
    }

    /** ランダムな有向グラフ。勾配つき・高速道路 (禁止) の辺を混ぜ、FLAT/WALK で結果が変わるようにする。 */
    private fun randomGraph(n: Int = 30, seed: Int = 7): List<List<E>> {
        val rnd = Random(seed)
        val types = listOf("residential", "tertiary", "cycleway", "path", "primary", "motorway")
        return List(n) { u ->
            val edges = ArrayList<E>()
            repeat(3) {
                val v = rnd.nextInt(n)
                if (v != u) edges += E(v, 5.0 + rnd.nextInt(200), types[rnd.nextInt(types.size)], if (rnd.nextInt(4) == 0) null else (rnd.nextInt(-12, 13) / 100.0))
            }
            edges += E((u + 1) % n, 50.0 + rnd.nextInt(50), "residential", 0.1) // 連結性のため
            edges
        }
    }

    private fun costOk(a: Double, b: Double) = abs(a - b) <= maxOf(1e-12, abs(a) * 1e-9)

    private fun buildCh(graph: LazyMappedRoadGraph) = ContractionHierarchyBuilder(maxWitnessSettled = 5).build(graph, GraphFingerprint.UNKNOWN).hierarchy

    private fun pathCost(graph: LazyMappedRoadGraph, path: List<Int>): Double {
        var total = 0.0; val cursor = graph.newRoutingEdgeCursor()
        for (i in 0 until path.size - 1) {
            cursor.reset(path[i]); var best = Double.POSITIVE_INFINITY
            while (cursor.next()) if (cursor.target == path[i + 1]) {
                val m = graph.routingEdgeMultiplier(cursor.type, cursor.grade, RoutePreference.RECOMMENDED, RouteCostMode.BICYCLE)
                if (!m.isNaN()) best = minOf(best, cursor.distance * m)
            }
            total += best
        }
        return total
    }

    @Test fun attachedRouteMatchesAStarForAllPairs() {
        graphFrom(randomGraph()).use { graph ->
            val ch = buildCh(graph)
            val n = graph.nodeCount
            val expected = Array(n) { s -> Array(n) { g -> graph.route(s, g) } }
            graph.attachContractionHierarchy(ch)
            assertTrue(graph.hasContractionHierarchy)
            var chUsed = 0
            for (s in 0 until n) for (g in 0 until n) {
                val a = expected[s][g]; val c = graph.route(s, g)
                assertEquals(a.isReachable, c.isReachable, "$s->$g reachability")
                if (s != g && graph.lastEngine == "ch") chUsed++
                if (!a.isReachable) { assertTrue(c.nodeIds.isEmpty()); assertEquals(Double.POSITIVE_INFINITY, c.totalCost); continue }
                assertTrue(costOk(a.totalCost, c.totalCost), "$s->$g cost ${a.totalCost} vs ${c.totalCost}")
                assertEquals(a.totalDistanceMeters, c.totalDistanceMeters, 1e-6, "$s->$g distance")
                assertEquals(s, c.nodeIndices.first()); assertEquals(g, c.nodeIndices.last())
                assertEquals(c.nodeIndices.size, c.nodeIds.size); assertEquals(c.nodeIndices.size, c.coordinates.size)
                assertEquals(c.nodeIndices.map { it + 1000L }, c.nodeIds)
                // 経路上の辺を実際にたどった総コストが A* のコストと一致する (同コスト別経路は許容)
                assertTrue(costOk(a.totalCost, pathCost(graph, c.nodeIndices)), "$s->$g path cost")
            }
            assertTrue(chUsed > 0, "CH should have been used")
        }
    }

    /** 呼ばれたら分かる偽の CH。CH を使ってはいけない条件で使われていないことの検証用。 */
    private fun bogusQuery(calls: IntArray) = LazyMappedRoadGraph.ChQuery { s, g, _, _ -> calls[0]++; ChRouteResult(intArrayOf(s, g), 12345.0, 1.0) }

    @Test fun nonDefaultConditionsStayOnAStar() {
        graphFrom(randomGraph()).use { graph ->
            val n = graph.nodeCount
            class Q(val label: String, val run: (Int, Int) -> MappedRouteResult)
            val queries = listOf(
                Q("flat") { s, g -> graph.route(s, g, preference = RoutePreference.FLAT) },
                Q("hill") { s, g -> graph.route(s, g, preference = RoutePreference.HILL_CLIMB) },
                Q("walk") { s, g -> graph.route(s, g, costMode = RouteCostMode.WALK) },
                Q("car") { s, g -> graph.route(s, g, costMode = RouteCostMode.CAR) },
                Q("heuristic") { s, g -> graph.route(s, g, heuristicMultiplier = 1.2) },
                Q("arterialOnly") { s, g -> graph.route(s, g, arterialOnly = true) },
                Q("penalty") { s, g -> graph.route(s, g, arterialPenaltyMultiplier = 1.5) },
                Q("nonArterialPenalty") { s, g -> graph.route(s, g, nonArterialPenaltyMultiplier = 1.5) },
                Q("bounds") { s, g -> graph.route(s, g, bounds = LazyMappedRoadGraph.SearchBounds(0.0, 90.0, 0.0, 180.0)) },
            )
            for (q in queries) {
                graph.attachChQuery(null)
                val plain = Array(n) { s -> Array(n) { g -> q.run(s, g) } }
                val calls = IntArray(1); graph.attachChQuery(bogusQuery(calls))
                for (s in 0 until n) for (g in 0 until n) {
                    val r = q.run(s, g)
                    assertEquals(plain[s][g], r, "${q.label} $s->$g must be identical to plain A*")
                    if (s != g) assertEquals("astar", graph.lastEngine, "${q.label} $s->$g")
                }
                assertEquals(0, calls[0], "${q.label}: CH must not be consulted")
            }
            // 対照: 既定条件では偽 CH が使われる (= 上の検証が空振りでない)
            val calls = IntArray(1); graph.attachChQuery(bogusQuery(calls))
            val r = graph.route(0, 5)
            assertEquals(1, calls[0]); assertEquals(12345.0, r.totalCost); assertEquals("ch", graph.lastEngine)
        }
    }

    @Test fun throwingChFallsBackToAStar() {
        graphFrom(randomGraph()).use { graph ->
            val n = graph.nodeCount
            val expected = Array(n) { s -> Array(n) { g -> graph.route(s, g) } }
            var thrown = 0
            graph.attachChQuery { _, _, _, _ -> thrown++; throw IllegalStateException("boom") }
            for (s in 0 until n) for (g in 0 until n) {
                assertEquals(expected[s][g], graph.route(s, g), "$s->$g")
                if (s != g) assertEquals("astar", graph.lastEngine)
            }
            assertTrue(thrown > 0)
        }
    }

    @Test fun cancelledBeforeStartReturnsCancelledAndStartEqualsGoalSkipsCh() {
        graphFrom(randomGraph()).use { graph ->
            val calls = IntArray(1); graph.attachChQuery(bogusQuery(calls))
            val c = graph.route(0, 3, isCancelled = { true })
            assertTrue(c.wasCancelled); assertFalse(c.isReachable); assertEquals(0, calls[0])
            val same = graph.route(4, 4)
            assertEquals(listOf(4), same.nodeIndices); assertEquals(0, calls[0])
        }
    }

    @Test fun attachedChPropagatesMidSearchCancellationAndNullMatchesBaseline() {
        // 1) ランダムグラフで isCancelled = null / { false } の一致と { true } のキャンセルを確認
        graphFrom(randomGraph()).use { graph ->
            val baseline = graph.route(0, 5)
            graph.attachContractionHierarchy(buildCh(graph))
            val withNull = graph.route(0, 5, isCancelled = null)
            assertEquals("ch", graph.lastEngine)
            assertFalse(withNull.wasCancelled)
            assertEquals(baseline.isReachable, withNull.isReachable)
            assertTrue(costOk(baseline.totalCost, withNull.totalCost))
            assertEquals(baseline.totalDistanceMeters, withNull.totalDistanceMeters, 1e-6)

            val withFalse = graph.route(0, 5, isCancelled = { false })
            assertEquals("ch", graph.lastEngine)
            assertEquals(withNull, withFalse)

            val immediate = graph.route(0, 5, isCancelled = { true })
            assertTrue(immediate.wasCancelled)
            assertFalse(immediate.isReachable)
            assertTrue(immediate.nodeIds.isEmpty())
            assertTrue(immediate.totalCost.isInfinite())
            assertEquals("ch", graph.lastEngine)
        }

        // 2) 2500 ノード上向き鎖で CH 探索ループ内の 1024 ステップ判定による途中キャンセルを単一グラフ route() 経由で検証
        val n = 2500
        val out = List(n) { u -> if (u + 1 < n) listOf(E(u + 1, 10.0)) else emptyList() }
        val edgeN = n - 1
        val ranks = IntArray(n) { it }
        val from = IntArray(edgeN) { it }
        val to = IntArray(edgeN) { it + 1 }
        val dist = DoubleArray(edgeN) { 10.0 }
        val minusOne = IntArray(edgeN) { -1 }

        graphFrom(out).use { graph ->
            val aStarBaseline = graph.route(0, n - 1)
            val edgeCost = graph.route(0, 1).totalCost
            val ch = ContractionHierarchy.create(
                n, edgeN, ranks, from, to, DoubleArray(edgeN) { edgeCost }, dist, minusOne, minusOne, minusOne, GraphFingerprint.UNKNOWN,
            )
            graph.attachContractionHierarchy(ch)
            val chBaseline = graph.route(0, n - 1, isCancelled = null)
            assertEquals("ch", graph.lastEngine)
            assertFalse(chBaseline.wasCancelled)
            assertTrue(chBaseline.isReachable)
            assertEquals(aStarBaseline.nodeIds, chBaseline.nodeIds)
            assertEquals(aStarBaseline.totalCost, chBaseline.totalCost, 1e-9)
            assertEquals(aStarBaseline.totalDistanceMeters, chBaseline.totalDistanceMeters, 1e-9)

            // chRouteIfApplicable 開始前 (1)、ch.route 開始前 (2)、ch.route ループ 1024 ステップ目 (3) で true
            var checks = 0
            val midCancelled = graph.route(0, n - 1, isCancelled = { ++checks >= 3 })
            assertEquals(3, checks)
            assertEquals("ch", graph.lastEngine)
            assertTrue(midCancelled.wasCancelled)
            assertFalse(midCancelled.isReachable)
            assertTrue(midCancelled.nodeIds.isEmpty())
            assertTrue(midCancelled.totalCost.isInfinite())
            assertTrue(midCancelled.totalDistanceMeters.isInfinite())

            // キャンセル後も同一グラフの次回 route() が正常に動くこと
            val afterCancel = graph.route(0, n - 1)
            assertEquals("ch", graph.lastEngine)
            assertEquals(chBaseline, afterCancel)

            // ChQuery が wasCancelled = true を返した場合も到達不能ではなく wasCancelled = true を返すこと
            var seenInQuery = false
            graph.attachChQuery { s, g, gr, cancel ->
                seenInQuery = cancel?.invoke() == true
                ChRouteResult(IntArray(0), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, wasCancelled = seenInQuery)
            }
            var queryChecks = 0
            val queryCancelled = graph.route(0, n - 1, isCancelled = { ++queryChecks >= 2 })
            assertTrue(seenInQuery)
            assertTrue(queryCancelled.wasCancelled)
            assertFalse(queryCancelled.isReachable)
            assertEquals("ch", graph.lastEngine)
        }
    }

    @Test fun loaderReportsMissingMismatchAndAttached() {
        val dir = Files.createTempDirectory("ch-loader").toFile().apply { deleteOnExit() }
        graphFrom(randomGraph()).use { graph ->
            val graphFile = File(dir, "Hiroshima.graph").apply { writeText("graph A"); deleteOnExit() }
            val missing = loadContractionHierarchy(graphFile, graph.nodeCount)
            assertEquals(ChLoadResult.Status.MISSING, missing.status); assertNull(missing.hierarchy)
            val ch = ContractionHierarchyBuilder(maxWitnessSettled = 5).build(graph, GraphFingerprint.fromFile(graphFile)).hierarchy
            val chFile = File(dir, "Hiroshima.graph.ch").apply { deleteOnExit() }
            ch.write(chFile)
            val attached = loadContractionHierarchy(graphFile, graph.nodeCount)
            assertEquals(ChLoadResult.Status.ATTACHED, attached.status); assertNotNull(attached.hierarchy)
            // 別の graph (識別が違う) に対する CH は取り付けない
            graphFile.writeText("graph B but different length")
            val mismatch = loadContractionHierarchy(graphFile, graph.nodeCount)
            assertEquals(ChLoadResult.Status.MISMATCH, mismatch.status); assertNull(mismatch.hierarchy)
            // ノード数の不一致
            graphFile.writeText("graph A")
            assertEquals(ChLoadResult.Status.MISMATCH, loadContractionHierarchy(graphFile, graph.nodeCount + 1).status)
            // Use a separate path so this also works while Windows still maps the valid CH.
            val corruptGraph = File(dir, "Corrupt.graph").apply { writeText("graph A"); deleteOnExit() }
            File(dir, "Corrupt.graph.ch").apply { writeText("garbage"); deleteOnExit() }
            assertEquals(ChLoadResult.Status.ERROR, loadContractionHierarchy(corruptGraph, graph.nodeCount).status)
            // 小文字名のフォールバック
            val mixedCaseGraph = File(dir, "Osaka.graph")
            val lower = File(dir, "osaka.graph.ch").apply { deleteOnExit() }
            ch.write(lower)
            val found = findContractionHierarchyFile(mixedCaseGraph)
            assertTrue(found != null && found.canonicalFile == lower.canonicalFile)
            lower.delete()
        }
    }

    @Test fun attachFromGraphLogsOneLine() {
        val dir = Files.createTempDirectory("ch-log").toFile().apply { deleteOnExit() }
        graphFrom(randomGraph()).use { graph ->
            val graphFile = File(dir, "X.graph").apply { writeText("g"); deleteOnExit() }
            val lines = ArrayList<String>()
            graph.attachContractionHierarchyFrom(graphFile) { lines += it }
            assertEquals(1, lines.size); assertTrue(lines[0].startsWith("CH_ATTACH name=X.graph result=missing ms="), lines[0])
            assertFalse(graph.hasContractionHierarchy)
        }
    }
}
