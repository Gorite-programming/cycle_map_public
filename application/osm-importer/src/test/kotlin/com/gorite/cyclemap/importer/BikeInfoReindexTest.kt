package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.*
import java.io.*
import kotlin.test.*

class BikeInfoReindexTest {
    private fun table(ref: String? = "2") = BikeInfoReindex(
        longArrayOf(10, 20, 10, 30, 50), longArrayOf(20, 10, 20, 40, 60),
        intArrayOf(0, 0, 0, 1, 1), listOf("trunk", "residential"), ref = ref,
    )
    private val tags = mapOf("highway" to "trunk", "ref" to "12; 2;20", "cycleway:left" to "lane",
        "sidewalk:right" to "yes", "bicycle" to "yes", "oneway" to "yes")

    @Test fun mapsDirectionsDuplicateGraphPairsAndRefTags() {
        val table = table()
        val way = OsmWay(100, longArrayOf(10, 20), tags)
        table.accept(way); table.accept(way)
        assertEquals(classifyBicycleRoad(tags, false).encode(), table.values[0])
        assertEquals(classifyBicycleRoad(tags, true).encode(), table.values[1])
        assertEquals(table.values[0], table.values[2])
        assertEquals(0, table.values[3])
        val stats = table.statistics()
        assertEquals(5, stats.total); assertEquals(3, stats.unique); assertEquals(2, stats.unmatched)
        assertEquals(2, stats.roadway[BikeAccess.ALLOWED.ordinal])
        assertEquals(1, stats.roadway[BikeAccess.FORBIDDEN.ordinal])
        assertEquals(2, stats.left[SideFacility.BIKE_LANE.ordinal])
        assertEquals(2, stats.flags[0])
        assertContentEquals(longArrayOf(3, 3, 3), stats.tags)
        assertEquals(3, table.statistics(refOnly = true).total)
        assertEquals(3, table.statistics(highway = 0).total)
        assertEquals(0, table.statistics(refOnly = true, highway = 1).total)
        val noSubstring = table("1"); noSubstring.accept(way)
        assertEquals(0, noSubstring.statistics(true).total)
    }

    @Test fun differentWaysAreAmbiguousEvenWithIdenticalRules() {
        val table = table()
        table.accept(OsmWay(1, longArrayOf(10, 20), tags))
        table.accept(OsmWay(2, longArrayOf(10, 20), tags - "ref"))
        table.accept(OsmWay(1, longArrayOf(10, 20), tags))
        assertContentEquals(intArrayOf(0, 0, 0, 0, 0), table.values)
        assertEquals(3, table.statistics().ambiguous)
        assertEquals(0, table.statistics().unique)
        assertEquals(3, table.statistics(true).ambiguous)
        assertContentEquals(longArrayOf(0, 0, 0), table.statistics().tags)
        table.accept(OsmWay(3, longArrayOf(30, 40), mapOf("highway" to "residential", "bicycle" to "no")))
        assertEquals(2, table.statistics().unmatched)
        val bytes = ByteArrayOutputStream()
        table.printStatistics(PrintStream(bytes))
        assertTrue(bytes.toString().contains("WARNING"))
        assertTrue(bytes.toString().contains("highway=trunk"))
    }

    @Test fun zeroEncodingStillCountsAsUniqueAndNoMatchesAreSafe() {
        val table = table()
        table.accept(OsmWay(9, longArrayOf(30, 40), mapOf("highway" to "residential")))
        assertEquals(0, table.values[3])
        assertEquals(1, table.statistics().unique)
        assertEquals(1, table.statistics().roadway[BikeAccess.ALLOWED.ordinal])
        val empty = BikeInfoReindex(longArrayOf(), longArrayOf(), intArrayOf(), emptyList())
        empty.accept(OsmWay(1, longArrayOf(1, 2), tags))
        assertFalse(empty.statistics().format().contains("NaN"))
    }

    @Test fun sidecarSelectsOnlyExcludedNotificationRoadsAndCountsRules() {
        val collector = ExcludedRoadCollector(ExcludedRoadBounds(33.99, 131.99, 34.01, 132.01))
        val selected = listOf(
            mapOf("highway" to "motorway", "bicycle" to "no"),
            mapOf("highway" to "trunk", "motorroad" to "yes"),
            mapOf("highway" to "footway", "bicycle" to "dismount"),
        )
        val ignored = listOf(
            mapOf("highway" to "residential"), mapOf("highway" to "motorway"),
            mapOf("highway" to "footway"), mapOf("highway" to "steps"),
            mapOf("highway" to "cycleway", "bicycle" to "no"),
            mapOf("highway" to "footway", "footway" to "sidewalk", "bicycle" to "no"),
            mapOf("highway" to "path", "bicycle" to "no"),
            mapOf("highway" to "track", "bicycle" to "no"),
            mapOf("highway" to "residential", "access" to "private"),
            mapOf("highway" to "trunk", "motorroad" to "yes", "bicycle" to "yes"),
        )
        (selected + ignored).forEachIndexed { i, tags -> collector.accept(OsmWay(i.toLong(), longArrayOf(10, 20), tags)) }
        collector.acceptNode(10, 34.0, 132.0); collector.acceptNode(20, 34.001, 132.001)
        val segments = collector.segments()
        assertEquals(6, segments.size)
        assertEquals(3, collector.writtenWays)
        assertEquals(mapOf("footway" to 2, "motorway" to 2, "trunk" to 2), collector.highwayCounts)
        assertEquals(mapOf("FORBIDDEN" to 4, "DISMOUNT" to 2), collector.roadwayCounts)
        assertEquals(4, collector.motorroadCount)
        selected.forEachIndexed { i, tags ->
            assertEquals(classifyBicycleRoad(tags, false), segments[i * 2].info)
            assertEquals(classifyBicycleRoad(tags, true), segments[i * 2 + 1].info)
            assertEquals(segments[i * 2].fromLat, segments[i * 2 + 1].toLat)
        }
        val file = File.createTempFile("excluded-reindex", ".bikerules").apply { deleteOnExit() }
        ExcludedRoadIndex.write(file, segments)
        assertEquals(6, assertNotNull(ExcludedRoadIndex.load(file)).candidatesNear(34.0, 132.0).size)
        val stats = ByteArrayOutputStream()
        collector.printStatistics(segments.size, PrintStream(stats))
        assertTrue(stats.toString().contains("sidecar.flag.MOTORROAD=4"))
    }

    @Test fun sidecarKeepsWayOrderForOnewaysIncludingReverseAndRoundabout() {
        for (extra in listOf(mapOf("oneway" to "yes"), mapOf("oneway" to "-1"), mapOf("oneway" to "true"),
            mapOf("oneway" to "1"), mapOf("junction" to "roundabout"))) {
            val collector = ExcludedRoadCollector(ExcludedRoadBounds(33.0, 131.0, 35.0, 133.0))
            val tags = mapOf("highway" to "trunk", "motorroad" to "yes", "sidewalk:left" to "yes") + extra
            collector.accept(OsmWay(1, longArrayOf(1, 2, 3), tags))
            (1L..3L).forEach { collector.acceptNode(it, 34.0 + it * 0.001, 132.0) }
            val segments = collector.segments()
            assertEquals(2, segments.size, extra.toString())
            assertEquals(34.001, segments[0].fromLat)
            assertEquals(34.002, segments[0].toLat)
            assertTrue(segments.all { it.info == classifyBicycleRoad(tags, false) })
        }
    }

    @Test fun sidecarBoundsUseGraphNodesAndMarginAndKeepCrossingSegments() {
        val (graph, _) = graphFixture()
        val bounds = assertNotNull(BikeInfoReindex.readGraph(graph).bounds)
        assertEquals(33.99, bounds.minLat); assertEquals(34.011, bounds.maxLat, 1e-10)
        assertEquals(131.99, bounds.minLon); assertEquals(132.011, bounds.maxLon, 1e-10)
        val collector = ExcludedRoadCollector(bounds)
        val tags = mapOf("highway" to "residential", "bicycle" to "no")
        collector.accept(OsmWay(1, longArrayOf(1, 2, 3, 4), tags))
        collector.acceptNode(1, 34.0, 131.9); collector.acceptNode(2, 34.0, 132.1)
        collector.acceptNode(3, 34.1, 132.1); collector.acceptNode(4, 34.2, 132.1)
        assertEquals(2, collector.segments().size)
        assertEquals(1, collector.writtenWays)
        val empty = ExcludedRoadCollector(null)
        empty.accept(OsmWay(1, longArrayOf(1, 2), tags))
        empty.acceptNode(1, 34.0, 132.0); empty.acceptNode(2, 34.001, 132.0)
        assertTrue(empty.segments().isEmpty())
    }

    @Test fun sidecarSkipsMissingNodesAndHonorsExplicitTwoWayRoundabout() {
        val collector = ExcludedRoadCollector(ExcludedRoadBounds(33.0, 131.0, 35.0, 133.0))
        collector.accept(OsmWay(1, longArrayOf(1, 2, 3), mapOf("highway" to "footway", "bicycle" to "dismount",
            "junction" to "roundabout", "oneway" to "no")))
        collector.acceptNode(1, 34.0, 132.0); collector.acceptNode(2, 34.001, 132.0)
        assertEquals(2, collector.segments().size)
        assertEquals(2, collector.segments().size)
        assertEquals(1, collector.writtenWays)
    }

    private fun graphFixture(): Pair<File, File> {
        val file = File.createTempFile("bikeinfo-reindex", ".graph").apply { deleteOnExit() }
        val graph = RoadGraph(
            linkedMapOf(20L to GraphNode(20, 34.0, 132.0), 10L to GraphNode(10, 34.001, 132.001)),
            linkedMapOf(20L to listOf(GraphEdge(20, 10, 10.0, "trunk", false)),
                10L to listOf(GraphEdge(10, 20, 10.0, "trunk", false))),
        )
        val writer = GraphBinaryWriter()
        writer.write(graph, file)
        val index = File(file.path + ".idx").apply { deleteOnExit() }
        writeGraphIndex(file, index, mapOf(20L to 12f, 10L to 13f),
            listOf(BIKEINFO_MAGIC, "国道2号"), listOf("left|through", null))
        return file to index
    }

    @Test fun graphOrderMatchesIndexOrdinalsAndOutputIsExactCopyPlusSection() {
        val (file, index) = graphFixture()
        val originalGraph = file.readBytes(); val originalIndex = index.readBytes()
        val table = BikeInfoReindex.readGraph(file, "2")
        table.accept(OsmWay(1, longArrayOf(10, 20), tags))
        val output = File(index.path + ".new").apply { deleteOnExit() }
        writeBikeInfoIndex(index, output, table.nodeCount, table.values)
        assertContentEquals(originalGraph, file.readBytes())
        assertContentEquals(originalIndex, index.readBytes())
        val result = output.readBytes()
        assertContentEquals(originalIndex, result.copyOfRange(0, originalIndex.size))
        DataInputStream(ByteArrayInputStream(result, originalIndex.size, result.size - originalIndex.size)).use { input ->
            assertEquals(BIKEINFO_MAGIC, input.readUTF()); assertEquals(2, input.readInt())
            table.values.forEach { assertEquals(it, input.readInt()) }
            assertEquals(-1, input.read())
        }
        LazyMappedRoadGraph.load(file, output).use { graph ->
            for (ordinal in 0 until table.size) {
                val expected = classifyBicycleRoad(tags, table.from[ordinal] == 20L)
                assertEquals(expected, graph.bicycleInfoOfEdge(ordinal))
            }
            for (node in 0 until graph.nodeCount) {
                graph.outgoingBranches(node).forEach { branch ->
                    val ordinal = table.from.indexOf(graph.nodeAt(node).id)
                    assertEquals(classifyBicycleRoad(tags, table.from[ordinal] == 20L), graph.bicycleInfoOfEdge(ordinal))
                    assertEquals(table.to[ordinal], graph.nodeAt(branch.targetIndex).id)
                }
            }
        }
        assertFails { writeBikeInfoIndex(output, File(output.path + ".twice"), table.nodeCount, table.values) }
        assertFails { writeBikeInfoIndex(index, index, table.nodeCount, table.values) }
        assertContentEquals(originalIndex, index.readBytes())
        assertFails { validateBikeInfoInput(index, 999, table.size) }
        assertFails { validateBikeInfoInput(index, table.nodeCount, 999) }
    }

    @Test fun insertsBikeInfoBeforeJunctionNameAndPreservesCanonicalOrder() {
        val file = File.createTempFile("bikeinfo-junction", ".graph").apply { deleteOnExit() }
        val graph = RoadGraph(
            linkedMapOf(20L to GraphNode(20, 34.0, 132.0), 10L to GraphNode(10, 34.001, 132.001)),
            linkedMapOf(
                20L to listOf(GraphEdge(20, 10, 10.0, "trunk", false)),
                10L to listOf(GraphEdge(10, 20, 10.0, "trunk", false)),
            ),
        )
        GraphBinaryWriter().write(graph, file)
        val index = File(file.path + ".idx").apply { deleteOnExit() }
        val junctions = mapOf(
            20L to JunctionNodeInfo("本町一丁目", "ほんまちいっちょうめ", 1),
            10L to JunctionNodeInfo("紙屋町", null, 2),
        )
        writeGraphIndex(
            file, index,
            mapOf(20L to 12f, 10L to 13f),
            listOf("国道2号", "県道41号"),
            listOf("left|through", null),
            junctions,
        )

        val table = BikeInfoReindex.readGraph(file, "2")
        table.accept(OsmWay(1, longArrayOf(10, 20), tags))
        val output = File(index.path + ".bike").apply { deleteOnExit() }
        writeBikeInfoIndex(index, output, table.nodeCount, table.values)

        // 区間の並びが ELEVATION -> ROADNAME -> TURNLANES -> BIKEINFO -> JUNCTIONNAME になっていることを検証
        val sectionOrder = mutableListOf<String>()
        DataInputStream(output.inputStream().buffered()).use { input ->
            assertEquals("CYCLEMAP_INDEX_V2", input.readUTF())
            val nc = input.readInt()
            repeat(nc) { input.readLong() }
            repeat(nc) { input.readInt() }
            repeat(nc) { input.readInt() }
            val ec = input.readInt()
            repeat(ec) { input.readInt() }
            while (input.available() > 0) {
                val magic = input.readUTF()
                sectionOrder += magic
                when (magic) {
                    ELEVATION_SECTION_MAGIC -> { assertEquals(nc, input.readInt()); repeat(nc) { input.readShort() } }
                    ROAD_NAME_SECTION_MAGIC, TURN_LANES_SECTION_MAGIC -> {
                        val c = input.readInt(); repeat(c) { input.readUTF() }
                        assertEquals(ec, input.readInt()); repeat(ec) { input.readInt() }
                    }
                    BIKEINFO_MAGIC -> { assertEquals(ec, input.readInt()); repeat(ec) { input.readInt() } }
                    JUNCTION_NAME_SECTION_MAGIC -> {
                        val n = input.readInt(); repeat(n) { input.readUTF() }
                        val r = input.readInt(); repeat(r) { input.readUTF() }
                        val e = input.readInt()
                        repeat(e) { input.readInt(); input.readInt(); input.readInt(); input.readByte() }
                    }
                    else -> fail("Unexpected section: $magic")
                }
            }
        }
        assertEquals(
            listOf(ELEVATION_SECTION_MAGIC, ROAD_NAME_SECTION_MAGIC, TURN_LANES_SECTION_MAGIC, BIKEINFO_MAGIC, JUNCTION_NAME_SECTION_MAGIC),
            sectionOrder,
        )

        // 両方の区間が LazyMappedRoadGraph で正しく読めること
        LazyMappedRoadGraph.load(file, output).use { lg ->
            assertEquals(classifyBicycleRoad(tags, true), lg.bicycleInfoOfEdge(0))
            assertEquals(classifyBicycleRoad(tags, false), lg.bicycleInfoOfEdge(1))
            val j0 = assertNotNull(lg.junctionNameNear(0))
            assertEquals("本町一丁目", j0.name)
            assertEquals("ほんまちいっちょうめ", j0.reading)
            assertEquals(1, j0.kind)
            val j1 = assertNotNull(lg.junctionNameNear(1))
            assertEquals("紙屋町", j1.name)
            assertNull(j1.reading)
            assertEquals(2, j1.kind)
        }

        // 二重追記はエラーになる
        assertFails { writeBikeInfoIndex(output, File(output.path + ".twice"), table.nodeCount, table.values) }
    }
}
