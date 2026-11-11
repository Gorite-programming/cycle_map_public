package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossPortalsFileTest {
    private data class E(val to: Long, val meters: Double, val type: String, val grade: Double?)
    private class Spec(val ids: LongArray, val edges: Map<Long, List<E>>)

    /** ChainChRoutingTest と同じ合成グラフ (全ノード同一座標)。 */
    private fun build(spec: Spec): LazyMappedRoadGraph {
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
        val file = File.createTempFile("portals-test", ".graph").apply { deleteOnExit() }
        val channel = RandomAccessFile(file, "r").channel
        val ctor = LazyMappedRoadGraph::class.java.getDeclaredConstructor(
            FileChannel::class.java, ByteBuffer::class.java, LongArray::class.java, DoubleArray::class.java,
            DoubleArray::class.java, LongArray::class.java, IntArray::class.java, IntArray::class.java,
            IntArray::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val g = ctor.newInstance(channel, ByteBuffer.wrap(bytes.toByteArray()), ids,
            DoubleArray(ids.size) { 34.0 }, DoubleArray(ids.size) { 132.0 },
            starts, first, counts, targets.toIntArray(), targets.size) as LazyMappedRoadGraph
        g.attachContractionHierarchy(ContractionHierarchyBuilder(maxWitnessSettled = 5).build(g, GraphFingerprint.UNKNOWN).hierarchy)
        return g
    }

    private val types = listOf("residential", "tertiary", "cycleway", "path", "primary", "motorway")

    /** k 個のグラフが隣同士で 6 ノードずつ重なる。ノードの並びは混ぜる (ID 順 ≠ ノード番号順)。 */
    private fun bands(seed: Int, k: Int): List<Spec> {
        val rnd = Random(seed)
        val width = 24; val overlap = 6
        return (0 until k).map { b ->
            val lo = b * (width - overlap); val ids = (lo until lo + width).map { it.toLong() }.shuffled(rnd)
            Spec(ids.toLongArray(), ids.associateWith { u ->
                (0 until 4).mapNotNull {
                    val v = ids[rnd.nextInt(ids.size)]
                    if (v == u) null else E(v, 5.0 + rnd.nextDouble() * 200, types[rnd.nextInt(types.size)], if (rnd.nextInt(4) == 0) null else rnd.nextInt(-12, 13) / 100.0)
                }
            })
        }
    }

    private val fpA = GraphFingerprint(1000, 11, 12)
    private val fpB = GraphFingerprint(2000, 21, 22)
    private fun tmp(name: String = "a__b.portals") = File(Files.createTempDirectory("portals").toFile().apply { deleteOnExit() }, name)

    private fun assertSamePortals(expected: CrossPortals, actual: CrossPortals?) {
        assertNotNull(actual)
        assertTrue(expected.nodeIds.contentEquals(actual.nodeIds), "nodeIds")
        assertTrue(expected.aIndices.contentEquals(actual.aIndices), "aIndices")
        assertTrue(expected.bIndices.contentEquals(actual.bIndices), "bIndices")
    }

    // (a) 書き出し→読み込みで portalsBetween と完全一致
    @Test fun roundTripMatchesPortalsBetween() {
        val (sa, sb) = bands(3, 2)
        val a = build(sa); val b = build(sb)
        val expected = portalsBetween(a, b)
        assertEquals(6, expected.size)
        assertTrue(expected.aIndices.toList() != expected.aIndices.sorted(), "test needs shuffled node order")
        val file = tmp()
        CrossPortalsFile.write(file, expected, fpA, fpB)
        assertEquals(CrossPortalsFile.HEADER_BYTES + 6L * CrossPortalsFile.RECORD_BYTES, file.length())
        assertSamePortals(expected, CrossPortalsFile.read(file, fpA, fpB))
        a.close(); b.close()
    }

    // (d) 逆向き (B→A) は A/B を入れ替えた同じ通り口
    @Test fun reversedPairSwapsSides() {
        val (sa, sb) = bands(4, 2)
        val a = build(sa); val b = build(sb)
        val file = tmp()
        CrossPortalsFile.write(file, portalsBetween(a, b), fpA, fpB)
        assertSamePortals(portalsBetween(b, a), CrossPortalsFile.read(file, fpB, fpA))
        a.close(); b.close()
    }

    // (b) 識別違い・切れ・マジック違い・壊れは「使えない」(null、例外なし)
    @Test fun unusableFilesReturnNull() {
        val (sa, sb) = bands(5, 2)
        val a = build(sa); val b = build(sb)
        val file = tmp()
        CrossPortalsFile.write(file, portalsBetween(a, b), fpA, fpB)
        val good = file.readBytes()
        assertNotNull(CrossPortalsFile.read(file, fpA, fpB))

        assertNull(CrossPortalsFile.read(File(file.parentFile, "missing.portals"), fpA, fpB), "missing")
        assertNull(CrossPortalsFile.read(file, fpA.copy(size = 1001), fpB), "A fingerprint")
        assertNull(CrossPortalsFile.read(file, fpA, fpB.copy(lastCrc = 99)), "B fingerprint")
        assertNull(CrossPortalsFile.read(file, fpA, fpA), "same graph twice")
        assertNull(CrossPortalsFile.read(file, GraphFingerprint.UNKNOWN, fpB), "unknown fingerprint never matches")

        fun withBytes(bytes: ByteArray, why: String) { file.writeBytes(bytes); assertNull(CrossPortalsFile.read(file, fpA, fpB), why) }
        withBytes(good.copyOf(good.size - 5), "truncated body")
        withBytes(good.copyOf(CrossPortalsFile.HEADER_BYTES - 1), "truncated header")
        withBytes(ByteArray(0), "empty")
        withBytes(good + byteArrayOf(0), "trailing bytes")
        withBytes(good.copyOf().also { it[0] = (it[0] + 1).toByte() }, "magic")
        withBytes(good.copyOf().also { it[7] = (it[7] + 1).toByte() }, "version")
        withBytes(good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, "body bit flip")
        withBytes(good.copyOf().also { it[11] = (it[11] + 1).toByte() }, "count")
        file.writeBytes(good); assertNotNull(CrossPortalsFile.read(file, fpA, fpB))
        a.close(); b.close()
    }

    @Test fun pairFileNameIsOrderIndependent() {
        assertEquals("Hiroshima__Okayama.portals", CrossPortalsFile.fileName(File("/x/Okayama.graph"), File("/x/Hiroshima.graph")))
        assertEquals("Hiroshima__Okayama.portals", CrossPortalsFile.fileName(File("Hiroshima.graph"), File("Okayama.graph")))
        // 実機では yamaguchi.graph だけ小文字。大文字小文字を無視して並べる
        assertEquals("Hiroshima__yamaguchi.portals", CrossPortalsFile.fileName(File("yamaguchi.graph"), File("Hiroshima.graph")))
    }

    /** graph ファイルの組から探して読む (向きは自動、ファイル名の大文字小文字は無視、だめなら null)。 */
    @Test fun loadForGraphFilesFindsEitherDirectionAndRejectsStaleGraph() {
        val dir = Files.createTempDirectory("portals-dir").toFile()
        val ga = File(dir, "yamaguchi.graph").apply { writeBytes(ByteArray(3000) { (it % 7).toByte() }) }
        val gb = File(dir, "Hiroshima.graph").apply { writeBytes(ByteArray(5000) { (it % 5).toByte() }) }
        val portals = CrossPortals(intArrayOf(4, 1, 9), intArrayOf(7, 8, 0), longArrayOf(10, 20, 30)) // a = Hiroshima, b = yamaguchi
        val out = File(dir, "Hiroshima__Yamaguchi.portals") // パイプラインの名前 (県名は先頭大文字)
        CrossPortalsFile.write(out, portals, GraphFingerprint.fromFile(gb), GraphFingerprint.fromFile(ga))
        assertSamePortals(portals, loadCrossPortals(gb, ga))
        assertSamePortals(CrossPortals(portals.bIndices, portals.aIndices, portals.nodeIds), loadCrossPortals(ga, gb))
        ga.appendBytes(byteArrayOf(1)) // graph を作り直した (識別が変わる)
        assertNull(loadCrossPortals(gb, ga)); assertNull(loadCrossPortals(ga, gb))
        assertNull(loadCrossPortals(gb, File(dir, "Okayama.graph")), "no file for the pair")
        dir.deleteRecursively()
    }

    private fun sameRoute(expected: CrossChainResult, actual: CrossChainResult?) {
        assertNotNull(actual)
        assertEquals(expected.isReachable, actual.isReachable)
        assertEquals(expected.nodeIds, actual.nodeIds)
        assertEquals(expected.totalCost, actual.totalCost)
        assertEquals(expected.totalDistanceMeters, actual.totalDistanceMeters)
        assertEquals(expected.portalIds, actual.portalIds)
        assertEquals(expected.parts, actual.parts)
    }

    // (c) 通り口ファイルを使った routeAcrossChainCh は、使わない場合と同じ結果。ID 索引は読まない
    @Test fun chainChWithPortalFilesMatchesIdIndexMatching() {
        var reachable = 0
        for (k in 2..4) for (seed in 1..6) {
            val specs = bands(seed * 10 + k, k)
            val graphs = specs.map { build(it) }
            val fps = graphs.indices.map { GraphFingerprint(100L + it, seed.toLong(), k.toLong()) }
            val files = (0 until k - 1).map { i -> tmp("p$i.portals").also { CrossPortalsFile.write(it, portalsBetween(graphs[i], graphs[i + 1]), fps[i], fps[i + 1]) } }
            var idIndexOpens = 0; var portalLoads = 0
            fun plain() = graphs.map { g -> ChainGraph({ g }, borrowed = true, openIdIndex = { idIndexOpens++; g.sortedNodeIdIndex() }) }
            fun withFiles(forward: Boolean) = graphs.mapIndexed { i, g ->
                ChainGraph({ g }, borrowed = true, openIdIndex = { idIndexOpens++; g.sortedNodeIdIndex() },
                    portalsToNext = if (i == k - 1) null else ({
                        portalLoads++
                        if (forward) CrossPortalsFile.read(files[i], fps[i], fps[i + 1])
                        // 逆向きに保存された組 (B__A) を A→B で使う
                        else CrossPortalsFile.read(tmp("r$i.portals").also { CrossPortalsFile.write(it, portalsBetween(graphs[i + 1], graphs[i]), fps[i + 1], fps[i]) }, fps[i], fps[i + 1])
                    }))
            }
            val rnd = Random(seed)
            repeat(5) {
                val start = rnd.nextInt(specs.first().ids.size); val goal = rnd.nextInt(specs.last().ids.size)
                val expected = routeAcrossChainCh(plain(), start, goal)!!
                if (expected.isReachable) reachable++
                for (forward in listOf(true, false)) {
                    idIndexOpens = 0; portalLoads = 0
                    val stages = ArrayList<String>()
                    sameRoute(expected, routeAcrossChainCh(withFiles(forward), start, goal, stageTimingSink = { stages += it }))
                    assertEquals(0, idIndexOpens, "ID index must not be read when the portal file is usable")
                    assertTrue(portalLoads in 1 until k)
                    assertTrue(stages.any { it.startsWith("CHAIN_STAGE name=portal_match") })
                    assertTrue(stages.any { it.startsWith("CHAIN_PORTALS") && it.contains("source=file") }, "$stages")
                }
            }
            graphs.forEach { it.close() }
        }
        assertTrue(reachable > 20, "only $reachable reachable routes")
    }

    /** 通り口ファイルが使えない (null)・読み込みが例外・中身が今の graph に合わないときは、従来の照合で同じ結果。 */
    @Test fun chainChFallsBackWhenPortalFileIsUnusable() {
        val specs = bands(77, 3)
        val graphs = specs.map { build(it) }
        val rnd = Random(9)
        val real = portalsBetween(graphs[0], graphs[1])
        val outOfRange = CrossPortals(real.aIndices.copyOf().also { it[0] = graphs[0].nodeCount }, real.bIndices, real.nodeIds)
        val wrongNode = CrossPortals(real.aIndices.copyOf().also { it[0] = it[1] }, real.bIndices, real.nodeIds) // ID と番号が合わない
        val providers: List<Pair<String, () -> CrossPortals?>> = listOf(
            "null" to { null }, "throws" to { error("broken portal file") }, "empty" to { CrossPortals(IntArray(0), IntArray(0), LongArray(0)) },
            "out of range" to { outOfRange }, "id/index mismatch" to { wrongNode },
        )
        var reachable = 0
        repeat(8) {
            val start = rnd.nextInt(specs.first().ids.size); val goal = rnd.nextInt(specs.last().ids.size)
            val expected = routeAcrossChainCh(graphs.map { ChainGraph({ it }, borrowed = true) }, start, goal)!!
            if (expected.isReachable) reachable++
            for ((label, provider) in providers) {
                val stages = ArrayList<String>()
                val actual = routeAcrossChainCh(graphs.mapIndexed { i, g -> ChainGraph({ g }, borrowed = true, portalsToNext = if (i == 0) provider else null) },
                    start, goal, stageTimingSink = { stages += it })
                sameRoute(expected, actual)
                assertTrue(stages.any { it.startsWith("CHAIN_PORTALS stage=0") && it.contains("source=match") }, "$label: $stages")
            }
        }
        assertTrue(reachable > 0)
        graphs.forEach { it.close() }
    }

    /** ファイルの b 側 (次の県のノード番号) が次の graph と合わないときは、ID から番号を引き直して同じ結果を返す。 */
    @Test fun chainChRepairsMismatchedNextGraphSide() {
        val specs = bands(55, 3)
        val graphs = specs.map { build(it) }
        val rnd = Random(4)
        var reachable = 0
        for (stage in 0..1) repeat(6) {
            val real = portalsBetween(graphs[stage], graphs[stage + 1])
            val rotated = CrossPortals(real.aIndices, IntArray(real.size) { real.bIndices[(it + 1) % real.size] }, real.nodeIds)
            val start = rnd.nextInt(specs.first().ids.size); val goal = rnd.nextInt(specs.last().ids.size)
            val expected = routeAcrossChainCh(graphs.map { ChainGraph({ it }, borrowed = true) }, start, goal)!!
            if (expected.isReachable) reachable++
            val stages = ArrayList<String>()
            sameRoute(expected, routeAcrossChainCh(graphs.mapIndexed { i, g -> ChainGraph({ g }, borrowed = true, portalsToNext = if (i == stage) ({ rotated }) else null) },
                start, goal, stageTimingSink = { stages += it }))
            assertTrue(stages.any { it.startsWith("CHAIN_PORTALS repair=b_side") }, "$stages")
        }
        assertTrue(reachable > 0)
        graphs.forEach { it.close() }
    }

    /** A* のチェーンは通り口ファイルを使わない (挙動を変えない)。 */
    @Test fun aStarChainIgnoresPortalFiles() {
        val specs = bands(21, 3)
        val graphs = specs.map { build(it) }
        var loads = 0
        val plain = routeAcrossChain(graphs.map { ChainGraph({ it }, borrowed = true) }, 0, 0)
        val r = routeAcrossChain(graphs.map { ChainGraph({ it }, borrowed = true, portalsToNext = { loads++; null }) }, 0, 0)
        assertEquals(0, loads); assertEquals(plain.nodeIds, r.nodeIds); assertEquals(plain.totalCost, r.totalCost)
        graphs.forEach { it.close() }
    }
}
