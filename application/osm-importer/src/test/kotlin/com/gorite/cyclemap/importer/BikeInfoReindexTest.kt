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
}
