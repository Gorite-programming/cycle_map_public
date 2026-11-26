package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.*

class JunctionNameSectionTest {

    private data class JunctionEntrySpec(
        val nodeIndex: Int,
        val nameId: Int,
        val readingId: Int,
        val kind: Int,
    )

    private fun buildGraphAndIndex(
        coords: List<Pair<Double, Double>>,
        bikeValues: IntArray = IntArray(maxOf(1, coords.size - 1)) { 0 },
        writeJunction: ((DataOutputStream) -> Unit)? = null,
    ): Pair<File, File> {
        val n = coords.size
        val e = maxOf(1, n - 1)
        val file = File.createTempFile("junction-reader", ".graph").apply { deleteOnExit() }
        val bytes = ByteArrayOutputStream()
        val starts = LongArray(n) { -1L }
        val firstOrdinals = IntArray(n) { -1 }
        val counts = IntArray(n)
        val targets = IntArray(e)
        DataOutputStream(bytes).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1"); out.writeInt(n)
            for (i in 0 until n) {
                out.writeLong(100L + i)
                out.writeDouble(coords[i].first)
                out.writeDouble(coords[i].second)
            }
            out.writeInt(e)
            for (i in 0 until e) {
                val from = i % n
                val to = (i + 1) % n
                if (starts[from] < 0) {
                    starts[from] = bytes.size().toLong()
                    firstOrdinals[from] = i
                }
                counts[from]++
                targets[i] = to
                out.writeLong(100L + from); out.writeLong(100L + to); out.writeDouble(50.0)
                out.writeUTF("primary"); out.writeBoolean(false); out.writeBoolean(false)
            }
        }
        file.writeBytes(bytes.toByteArray())

        val index = File(file.path + ".idx").apply { deleteOnExit() }
        DataOutputStream(index.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2"); out.writeInt(n)
            starts.forEach(out::writeLong)
            firstOrdinals.forEach(out::writeInt)
            counts.forEach(out::writeInt)
            out.writeInt(e); targets.forEach(out::writeInt)
            out.writeUTF("CYCLEMAP_ELEVATION_V1"); out.writeInt(n)
            repeat(n) { out.writeShort(150) }
            out.writeUTF("CYCLEMAP_ROADNAME_V1"); out.writeInt(1); out.writeUTF("国道2号"); out.writeInt(e)
            repeat(e) { out.writeInt(0) }
            out.writeUTF("CYCLEMAP_TURNLANES_V1"); out.writeInt(1); out.writeUTF("left|through"); out.writeInt(e)
            repeat(e) { out.writeInt(0) }
            out.writeUTF("CYCLEMAP_BIKEINFO_V1"); out.writeInt(e)
            for (i in 0 until e) out.writeInt(bikeValues.getOrElse(i) { 0 })
            writeJunction?.invoke(out)
        }
        return file to index
    }

    private fun writeValidJunctionSection(
        out: DataOutputStream,
        names: List<String>,
        readings: List<String>,
        entries: List<JunctionEntrySpec>,
    ) {
        out.writeUTF("CYCLEMAP_JUNCTIONNAME_V1")
        out.writeInt(names.size); names.forEach(out::writeUTF)
        out.writeInt(readings.size); readings.forEach(out::writeUTF)
        out.writeInt(entries.size)
        for (e in entries) {
            out.writeInt(e.nodeIndex)
            out.writeInt(e.nameId)
            out.writeInt(e.readingId)
            out.writeByte(e.kind)
        }
    }

    @Test
    fun roundTripReadsJunctionNamesReadingsAndKinds() {
        val bikeInfo = classifyBicycleRoad(mapOf("highway" to "primary", "cycleway:left" to "lane"), false)
        val coords = listOf(
            34.3950 to 132.4550,
            34.3955 to 132.4550,
            34.3960 to 132.4550,
        )
        val (file, index) = buildGraphAndIndex(coords, intArrayOf(bikeInfo.encode(), 0)) { out ->
            writeValidJunctionSection(
                out,
                listOf("八丁堀", "紙屋町"),
                listOf("はっちょうぼり"),
                listOf(
                    JunctionEntrySpec(0, 0, 0, 1),
                    JunctionEntrySpec(2, 1, -1, 2),
                ),
            )
        }
        LazyMappedRoadGraph.load(file, index).use { lg ->
            assertTrue(lg.hasJunctionNames)
            assertEquals(bikeInfo, lg.bicycleInfoOfEdge(0))
            assertEquals("国道2号", lg.roadNameOfEdge(0))

            val j0 = assertNotNull(lg.junctionNameNear(0))
            assertEquals(JunctionName("八丁堀", "はっちょうぼり", 1, 0, 0.0), j0)

            val j2 = assertNotNull(lg.junctionNameNear(2))
            assertEquals(JunctionName("紙屋町", null, 2, 2, 0.0), j2)

            // node 1 は 0・2 の両方から約 55.6 m 離れているため半径 35 m では null
            assertNull(lg.junctionNameNear(1, 35.0))
        }
    }

    @Test
    fun absentSectionReturnsNullForJunctionNameNear() {
        val (file, index) = buildGraphAndIndex(listOf(34.3950 to 132.4550, 34.3951 to 132.4550))
        LazyMappedRoadGraph.load(file, index).use { lg ->
            assertFalse(lg.hasJunctionNames)
            assertNull(lg.junctionNameNear(0))
            assertNull(lg.junctionNameNear(1))
        }
    }

    @Test
    fun corruptedOrTruncatedJunctionSectionIsIgnoredWhileEarlierSectionsRemainReadable() {
        val bikeInfo = classifyBicycleRoad(mapOf("highway" to "primary", "sidewalk:right" to "yes"), false)
        val coords = listOf(34.3950 to 132.4550, 34.3951 to 132.4550)

        val brokenWriters: List<(DataOutputStream) -> Unit> = listOf(
            // 負の nameCount
            { out -> out.writeUTF("CYCLEMAP_JUNCTIONNAME_V1"); out.writeInt(-1) },
            // 残りバイトを超える nameCount (OOM 防止チェック)
            { out -> out.writeUTF("CYCLEMAP_JUNCTIONNAME_V1"); out.writeInt(Int.MAX_VALUE) },
            // 残りバイトを超える entryCount
            { out ->
                out.writeUTF("CYCLEMAP_JUNCTIONNAME_V1")
                out.writeInt(1); out.writeUTF("本町")
                out.writeInt(0)
                out.writeInt(100)
            },
            // エントリの途中で切れている
            { out ->
                out.writeUTF("CYCLEMAP_JUNCTIONNAME_V1")
                out.writeInt(1); out.writeUTF("本町")
                out.writeInt(0)
                out.writeInt(1)
                out.writeInt(0); out.writeInt(0) // readingId と kind が欠落
            },
            // nodeIndex が範囲外
            { out ->
                writeValidJunctionSection(out, listOf("本町"), emptyList(), listOf(JunctionEntrySpec(99, 0, -1, 1)))
            },
            // nameId が範囲外
            { out ->
                writeValidJunctionSection(out, listOf("本町"), emptyList(), listOf(JunctionEntrySpec(0, 5, -1, 1)))
            },
            // readingId が範囲外 (-1 以外の負値や上限超え)
            { out ->
                writeValidJunctionSection(out, listOf("本町"), emptyList(), listOf(JunctionEntrySpec(0, 0, 0, 1)))
            },
        )

        for (writer in brokenWriters) {
            val (file, index) = buildGraphAndIndex(coords, intArrayOf(bikeInfo.encode()), writer)
            LazyMappedRoadGraph.load(file, index).use { lg ->
                assertFalse(lg.hasJunctionNames)
                assertNull(lg.junctionNameNear(0))
                assertEquals(15.0, lg.elevationMetersAt(0))
                assertEquals("国道2号", lg.roadNameOfEdge(0))
                assertEquals("left|through", lg.turnLanesOfEdge(0))
                assertEquals(bikeInfo, lg.bicycleInfoOfEdge(0))
            }
        }
    }

    @Test
    fun radiusBoundaryAndTieBreakingPreferSignalOverJunctionYes() {
        // node 0: クエリ基準点
        // node 1: node 0 から北に約 30.0 m (信号)
        // node 2: node 0 から南に同じ距離 (約 30.0 m, junction=yes)
        // node 3: node 0 から東に約 40.0 m (信号)
        val baseLat = 34.4000
        val baseLon = 132.4500
        val dLat30m = 30.0 / 111_194.9266 // haversine で約 30.0 m
        val coords = listOf(
            baseLat to baseLon,
            (baseLat + dLat30m) to baseLon,
            (baseLat - dLat30m) to baseLon,
            baseLat to (baseLon + 0.00045),
        )
        val d1 = haversineMeters(coords[0].first, coords[0].second, coords[1].first, coords[1].second)
        val d2 = haversineMeters(coords[0].first, coords[0].second, coords[2].first, coords[2].second)
        val d3 = haversineMeters(coords[0].first, coords[0].second, coords[3].first, coords[3].second)
        assertEquals(d1, d2, 1e-9)
        assertTrue(d1 < 35.0)
        assertTrue(d3 > 35.0)

        // junction=yes (node 2) を先に並べても、同距離なら種別 1 (信号, node 1) が選ばれること
        val (file, index) = buildGraphAndIndex(coords) { out ->
            writeValidJunctionSection(
                out,
                listOf("南交差点", "北信号", "東信号"),
                emptyList(),
                listOf(
                    JunctionEntrySpec(1, 1, -1, 1),
                    JunctionEntrySpec(2, 0, -1, 2),
                    JunctionEntrySpec(3, 2, -1, 1),
                ),
            )
        }
        LazyMappedRoadGraph.load(file, index).use { lg ->
            // 境界内 (35 m): 同距離で種別 1 が優先
            val hit = assertNotNull(lg.junctionNameNear(0, 35.0))
            assertEquals("北信号", hit.name)
            assertEquals(1, hit.kind)
            assertEquals(1, hit.nodeIndex)
            assertEquals(d1, hit.distanceMeters, 1e-6)

            // 半径ちょうど d1 ならヒット、それよりわずかに小さいと null
            assertNotNull(lg.junctionNameNear(0, d1))
            assertNull(lg.junctionNameNear(0, d1 - 0.01))

            // 不正なノード番号や負の半径は null
            assertNull(lg.junctionNameNear(-1, 35.0))
            assertNull(lg.junctionNameNear(999, 35.0))
            assertNull(lg.junctionNameNear(0, -1.0))
        }
    }

    @Test
    fun gridSearchFindsNearestAcrossManyJunctionEntries() {
        // 25 個の交差点ノードを用意して格子探索パス (nodeIndices.size > 16) を確実に通す
        val coords = (0 until 25).map { i ->
            (34.4000 + i * 0.0005) to (132.4500 + (i % 5) * 0.0005)
        } + listOf(34.4050 to 132.4502) // query node = index 25 (index 10 = (34.4050, 132.4500) から約 18 m)

        val names = (0 until 25).map { "交差点$it" }
        val entries = (0 until 25).map { JunctionEntrySpec(it, it, -1, if (it % 2 == 0) 1 else 2) }
        val (file, index) = buildGraphAndIndex(coords) { out ->
            writeValidJunctionSection(out, names, emptyList(), entries)
        }
        LazyMappedRoadGraph.load(file, index).use { lg ->
            val nearest = assertNotNull(lg.junctionNameNear(25, 35.0))
            assertEquals("交差点10", nearest.name)
            assertEquals(10, nearest.nodeIndex)
            assertTrue(nearest.distanceMeters < 25.0)
        }
    }

    @Test
    fun legacyReaderStopsAtUnknownJunctionSectionAndStillReadsBikeInfo() {
        val bikeInfo = classifyBicycleRoad(mapOf("highway" to "primary", "cycleway:left" to "lane"), false)
        val coords = listOf(34.3950 to 132.4550, 34.3951 to 132.4550)
        val (_, index) = buildGraphAndIndex(coords, intArrayOf(bikeInfo.encode())) { out ->
            writeValidJunctionSection(out, listOf("八丁堀"), listOf("はっちょうぼり"), listOf(JunctionEntrySpec(0, 0, 0, 1)))
        }

        // 古いアプリの readTrailingSections を模擬: ELEVATION / ROADNAME / TURNLANES / BIKEINFO しか知らず、
        // 知らない目印 (CYCLEMAP_JUNCTIONNAME_V1) に当たると即 return する。
        var readElevation = false
        var readRoadName = false
        var readTurnLanes = false
        var readBikeBits: Int? = null
        var stoppedOnUnknown: String? = null

        DataInputStream(index.inputStream().buffered()).use { input ->
            assertEquals("CYCLEMAP_INDEX_V2", input.readUTF())
            val nc = input.readInt()
            repeat(nc) { input.readLong() }
            repeat(nc) { input.readInt() }
            repeat(nc) { input.readInt() }
            val ec = input.readInt()
            repeat(ec) { input.readInt() }
            while (input.available() > 0) {
                when (val magic = input.readUTF()) {
                    "CYCLEMAP_ELEVATION_V1" -> {
                        assertEquals(nc, input.readInt())
                        repeat(nc) { input.readShort() }
                        readElevation = true
                    }
                    "CYCLEMAP_ROADNAME_V1" -> {
                        val c = input.readInt(); repeat(c) { input.readUTF() }
                        assertEquals(ec, input.readInt()); repeat(ec) { input.readInt() }
                        readRoadName = true
                    }
                    "CYCLEMAP_TURNLANES_V1" -> {
                        val c = input.readInt(); repeat(c) { input.readUTF() }
                        assertEquals(ec, input.readInt()); repeat(ec) { input.readInt() }
                        readTurnLanes = true
                    }
                    "CYCLEMAP_BIKEINFO_V1" -> {
                        assertEquals(ec, input.readInt())
                        readBikeBits = input.readInt()
                        repeat(ec - 1) { input.readInt() }
                    }
                    else -> {
                        stoppedOnUnknown = magic
                        break
                    }
                }
            }
        }
        assertTrue(readElevation)
        assertTrue(readRoadName)
        assertTrue(readTurnLanes)
        assertEquals(bikeInfo.encode(), readBikeBits)
        assertEquals("CYCLEMAP_JUNCTIONNAME_V1", stoppedOnUnknown)
    }

    @Test
    fun routeInstructionDefaultsJunctionFieldsToNull() {
        val inst = RouteInstruction(
            type = InstructionType.RIGHT_TURN,
            nodeId = 1L,
            routeIndex = 1,
            distanceFromStartMeters = 100.0,
            turnAngleDegrees = 90.0,
            junctionBranches = 4,
            incomingRoadType = "primary",
            outgoingRoadType = "secondary",
            reason = "turn-right",
        )
        assertNull(inst.junctionName)
        assertNull(inst.junctionReading)
    }
}
