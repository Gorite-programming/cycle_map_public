package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.*
import org.openstreetmap.osmosis.osmbinary.Fileformat
import org.openstreetmap.osmosis.osmbinary.Osmformat
import com.google.protobuf.ByteString
import java.io.DataOutputStream
import java.io.File
import kotlin.test.*

class BicycleDirectionImportTest {
    private fun pbf(tags: Map<String, String>): File {
        val strings = listOf("") + (tags.keys + tags.values).distinct()
        val table = Osmformat.StringTable.newBuilder()
        strings.forEach { table.addS(ByteString.copyFromUtf8(it)) }
        val way = Osmformat.Way.newBuilder().setId(10).addRefs(1).addRefs(1)
        tags.forEach { (key, value) -> way.addKeys(strings.indexOf(key)); way.addVals(strings.indexOf(value)) }
        val group = Osmformat.PrimitiveGroup.newBuilder()
            .addNodes(Osmformat.Node.newBuilder().setId(1).setLat(340000000).setLon(1310000000))
            .addNodes(Osmformat.Node.newBuilder().setId(2).setLat(340010000).setLon(1310000000))
            .addWays(way)
        val block = Osmformat.PrimitiveBlock.newBuilder().setStringtable(table).addPrimitivegroup(group).build()
        val file = File.createTempFile("bicycle-direction", ".osm.pbf").apply { deleteOnExit() }
        DataOutputStream(file.outputStream()).use { out ->
            fun write(type: String, bytes: ByteArray) {
                val blob = Fileformat.Blob.newBuilder().setRaw(ByteString.copyFrom(bytes)).build().toByteArray()
                val header = Fileformat.BlobHeader.newBuilder().setType(type).setDatasize(blob.size).build().toByteArray()
                out.writeInt(header.size); out.write(header); out.write(blob)
            }
            write("OSMHeader", Osmformat.HeaderBlock.newBuilder().addRequiredFeatures("OsmSchema-V0.6").build().toByteArray())
            write("OSMData", block.toByteArray())
        }
        return file
    }

    @Test fun importedDirectionsAgreeWithWarningsAStarAndCH() {
        val cases = listOf(
            Triple(mapOf("junction" to "roundabout"), true, false),
            Triple(mapOf("oneway" to "yes", "oneway:bicycle" to "no"), true, true),
            Triple(mapOf("oneway:bicycle" to "yes"), true, false),
            Triple(mapOf("oneway" to "-1"), false, true),
            Triple(mapOf("oneway" to "no", "junction" to "roundabout"), true, true),
            Triple(mapOf("oneway" to "yes", "cycleway" to "opposite_lane"), true, true),
            Triple(mapOf("oneway" to "yes", "cycleway:right" to "lane", "cycleway:right:oneway" to "no"), true, true),
        )
        for ((extra, forward, reverse) in cases) {
            val tags = extra + ("highway" to "residential")
            val input = pbf(tags)
            val nodes = mapOf(1L to GraphNode(1, 34.0, 131.0), 2L to GraphNode(2, 34.001, 131.0))
            val graphs = listOf(
                OsmGraphBuilder().build(nodes, listOf(OsmWay(10, longArrayOf(1, 2), tags))),
                TwoPassGraphBuilder().build(input),
            )
            assertEquals(if (forward && reverse) 2L else 1L, collectBicycleNetworkStats(input).directedEdges, "$tags stats")
            for (graph in graphs) {
                val edges = graph.outgoing.values.flatten()
                assertEquals(if (forward && reverse) 2 else 1, edges.size, "$tags edges")
                assertTrue(edges.all { it.oneWay == !(forward && reverse) }, "$tags flags")
                val file = File.createTempFile("bicycle-direction", ".graph").apply { deleteOnExit() }
                GraphBinaryWriter().write(graph, file)
                val index = File(file.path + ".idx").apply { deleteOnExit() }
                writeGraphIndex(file, index)
                LazyMappedRoadGraph.load(file).use { mapped ->
                    val ch = ContractionHierarchyBuilder().build(mapped, GraphFingerprint.fromFile(file)).hierarchy
                    for ((from, to, allowed) in listOf(Triple(1L, 2L, forward), Triple(2L, 1L, reverse))) {
                        val a = AStarRouter(graph, CyclingCostModel).route(from, to)
                        val s = (0 until mapped.nodeCount).first { mapped.nodeAt(it).id == from }
                        val g = (0 until mapped.nodeCount).first { mapped.nodeAt(it).id == to }
                        val ma = mapped.route(s, g)
                        val c = ch.route(s, g, mapped)
                        assertEquals(allowed, a.isReachable, "$tags $from->$to memory")
                        assertEquals(allowed, ma.isReachable, "$tags $from->$to mmap")
                        assertEquals(allowed, c.isReachable, "$tags $from->$to CH")
                        if (allowed) {
                            assertEquals(a.totalCost, ma.totalCost, 1e-6)
                            assertEquals(ma.totalCost, c.totalCost, 1e-6)
                            assertEquals(ma.nodeIndices, c.nodeIndices.toList())
                        }
                        val info = classifyBicycleRoad(tags, from == 2L)
                        assertEquals(allowed, info.roadway == BikeAccess.ALLOWED, "$tags warning")
                        if (forward && reverse && extra["oneway"] == "yes") {
                            assertTrue(info.flags and BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED != 0)
                        }
                    }
                }
            }
        }
    }
}
