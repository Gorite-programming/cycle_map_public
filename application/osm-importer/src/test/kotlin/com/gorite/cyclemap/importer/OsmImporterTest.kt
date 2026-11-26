package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphEdge
import com.gorite.cyclemap.routing.GraphNode
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RoadGraph
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsmImporterTest {
    @Test
    fun `access filtering does not discard road types`() {
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "primary", "bicycle" to "no")))
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "primary")))
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "track")))
    }

    @Test
    fun `access filtering excludes ways cyclists cannot use`() {
        // 階段・工事中・歩行者専用道は bicycle の明示許可がない限り通さない
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "steps")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "steps", "bicycle" to "yes")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "construction")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "footway")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "pedestrian")))
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "footway", "bicycle" to "yes")))
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "pedestrian", "bicycle" to "designated")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "path", "bicycle" to "dismount")))
        // 自動車専用道路
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "trunk", "motorroad" to "yes")))
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "trunk")))
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "cycleway")))
    }

    @Test
    fun `explicit bicycle permission overrides access and vehicle restrictions`() {
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "path", "vehicle" to "no", "bicycle" to "yes")))
        assertTrue(BicycleAccessFilter.isAllowed(mapOf("highway" to "track", "access" to "private", "bicycle" to "designated")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "track", "access" to "private")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "path", "vehicle" to "no")))
        // bicycle=no は access より優先して除外のまま、階段は明示許可でも通さない
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "path", "access" to "yes", "bicycle" to "no")))
        assertFalse(BicycleAccessFilter.isAllowed(mapOf("highway" to "steps", "access" to "no", "bicycle" to "yes")))
    }

    @Test
    fun `graph keeps highway and applies one way direction`() {
        val nodes = mapOf(
            1L to GraphNode(1L, 34.0, 131.0),
            2L to GraphNode(2L, 34.001, 131.0),
            3L to GraphNode(3L, 34.002, 131.0),
        )
        val graph = OsmGraphBuilder().build(
            nodes,
            listOf(OsmWay(10L, longArrayOf(1, 2, 3), mapOf("highway" to "secondary", "oneway" to "yes"))),
        )

        val edges = graph.outgoing.values.flatten()
        assertEquals(2, edges.size)
        assertTrue(edges.all { it.roadType == "secondary" })
        assertTrue(edges.all { it.oneWay })
        assertTrue(edges.none { it.from == 2L && it.to == 1L })
    }

    @Test
    fun `japan bbox covers tokyo and okinawa`() {
        val tokyo = GraphNode(1L, 35.68, 139.76)
        val naha = GraphNode(2L, 26.21, 127.68)
        val yamaguchi = GraphNode(3L, 34.18, 131.47)
        assertTrue(GeoBBox.JAPAN.contains(tokyo))
        assertTrue(GeoBBox.JAPAN.contains(naha))
        assertTrue(GeoBBox.JAPAN.contains(yamaguchi))
        assertFalse(GeoBBox.YAMAGUCHI.contains(tokyo))
    }

    @Test
    fun `extractJunctionNodeInfo selects signals and junction yes and excludes others`() {
        val signal = extractJunctionNodeInfo(mapOf("highway" to "traffic_signals", "name" to "八丁堀交差点", "name:ja-Hira" to "はっちょうぼり"))
        assertEquals(JunctionNodeInfo("八丁堀", "はっちょうぼり", 1), signal)

        val jyes = extractJunctionNodeInfo(mapOf("junction" to "yes", "name" to "紙屋町"))
        assertEquals(JunctionNodeInfo("紙屋町", null, 2), jyes)

        // 信号と junction=yes の両方がある場合は種別 1 (信号) を優先
        val both = extractJunctionNodeInfo(mapOf("highway" to "traffic_signals", "junction" to "yes", "name" to "十日市"))
        assertEquals(JunctionNodeInfo("十日市", null, 1), both)

        // バス停・横断歩道・高速出入口は含めない
        assertNull(extractJunctionNodeInfo(mapOf("highway" to "crossing", "name" to "駅前横断歩道")))
        assertNull(extractJunctionNodeInfo(mapOf("highway" to "bus_stop", "name" to "市役所前")))
        assertNull(extractJunctionNodeInfo(mapOf("highway" to "motorway_junction", "name" to "広島IC")))
        assertNull(extractJunctionNodeInfo(mapOf("highway" to "traffic_signals")))
    }

    @Test
    fun `writeGraphIndex writes JUNCTIONNAME section in ascending nodeIndex order`() {
        val dir = Files.createTempDirectory("junction-index").toFile()
        val n1 = GraphNode(100L, 34.3950, 132.4550)
        val n2 = GraphNode(200L, 34.3950, 132.4552)
        val n3 = GraphNode(300L, 34.3960, 132.4550)
        val graph = RoadGraph(
            linkedMapOf(100L to n1, 200L to n2, 300L to n3),
            linkedMapOf(
                100L to listOf(GraphEdge(100L, 200L, 18.0, "primary", roadName = "相生通り")),
                200L to listOf(GraphEdge(200L, 100L, 18.0, "primary", roadName = "相生通り"), GraphEdge(200L, 300L, 111.0, "secondary")),
                300L to listOf(GraphEdge(300L, 200L, 111.0, "secondary")),
            ),
        )
        val graphFile = File(dir, "test.graph")
        val indexFile = File(dir, "test.graph.idx")
        val writer = GraphBinaryWriter()
        writer.write(graph, graphFile)
        // わざと逆順 (300 -> 100) で渡しても nodeIndex 昇順で書き出されることを検証
        val junctions = linkedMapOf(
            300L to JunctionNodeInfo("本町", null, 2),
            100L to JunctionNodeInfo("紙屋町", "かみやちょう", 1),
        )
        writeGraphIndex(graphFile, indexFile, null, writer.edgeRoadNames, null, junctions)

        // バイナリ形式で nodeIndex 昇順かつフォーマットどおり書かれているか確認
        DataInputStream(indexFile.inputStream().buffered()).use { input ->
            assertEquals("CYCLEMAP_INDEX_V2", input.readUTF())
            val nc = input.readInt(); assertEquals(3, nc)
            repeat(nc) { input.readLong() }
            repeat(nc) { input.readInt() }
            repeat(nc) { input.readInt() }
            val ec = input.readInt(); assertEquals(4, ec)
            repeat(ec) { input.readInt() }
            assertEquals(ROAD_NAME_SECTION_MAGIC, input.readUTF())
            val rnCount = input.readInt(); repeat(rnCount) { input.readUTF() }
            assertEquals(ec, input.readInt()); repeat(ec) { input.readInt() }

            assertEquals(JUNCTION_NAME_SECTION_MAGIC, input.readUTF())
            assertEquals(2, input.readInt())
            assertEquals("紙屋町", input.readUTF())
            assertEquals("本町", input.readUTF())
            assertEquals(1, input.readInt())
            assertEquals("かみやちょう", input.readUTF())
            assertEquals(2, input.readInt())
            // entry 0: nodeIndex=0 (100L)
            assertEquals(0, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals(1, input.readByte().toInt())
            // entry 1: nodeIndex=2 (300L)
            assertEquals(2, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(-1, input.readInt())
            assertEquals(2, input.readByte().toInt())
            assertEquals(-1, input.read())
        }

        // LazyMappedRoadGraph で読み込んで検索できること
        LazyMappedRoadGraph.load(graphFile, indexFile).use { lg ->
            assertTrue(lg.hasJunctionNames)
            val j0 = assertNotNull(lg.junctionNameNear(lg.nodeIndexOf(100L)))
            assertEquals("紙屋町", j0.name)
            assertEquals("かみやちょう", j0.reading)
            assertEquals(1, j0.kind)
            assertEquals(0.0, j0.distanceMeters, 1e-6)

            // 200L は 100L から約 18m なので 35m 以内で「紙屋町」がヒットする
            val j1 = assertNotNull(lg.junctionNameNear(lg.nodeIndexOf(200L), 35.0))
            assertEquals("紙屋町", j1.name)
            assertEquals(lg.nodeIndexOf(100L), j1.nodeIndex)

            val j2 = assertNotNull(lg.junctionNameNear(lg.nodeIndexOf(300L)))
            assertEquals("本町", j2.name)
            assertNull(j2.reading)
            assertEquals(2, j2.kind)
        }
    }
}
