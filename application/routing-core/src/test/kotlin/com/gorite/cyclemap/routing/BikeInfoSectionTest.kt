package com.gorite.cyclemap.routing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.*

class BikeInfoSectionTest {
    private fun fixture(count: Int? = null, values: IntArray = intArrayOf()): Pair<File, File> {
        val file = File.createTempFile("bikeinfo-reader", ".graph").apply { deleteOnExit() }
        val bytes = ByteArrayOutputStream()
        val starts = LongArray(3) { -1 }
        DataOutputStream(bytes).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1"); out.writeInt(3)
            repeat(3) { out.writeLong(10L + it); out.writeDouble(34.0); out.writeDouble(132.0 + it * .001) }
            out.writeInt(3)
            listOf(0 to 1, 0 to 2, 1 to 0).forEach { (from, to) ->
                if (starts[from] < 0) starts[from] = bytes.size().toLong()
                out.writeLong(10L + from); out.writeLong(10L + to); out.writeDouble(100.0)
                out.writeUTF("residential"); out.writeBoolean(false); out.writeBoolean(false)
            }
        }
        file.writeBytes(bytes.toByteArray())
        val index = File(file.path + ".idx").apply { deleteOnExit() }
        DataOutputStream(index.outputStream()).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2"); out.writeInt(3)
            starts.forEach(out::writeLong)
            intArrayOf(0, 2, -1).forEach(out::writeInt)
            intArrayOf(2, 1, 0).forEach(out::writeInt)
            out.writeInt(3); intArrayOf(1, 2, 0).forEach(out::writeInt)
            out.writeUTF("CYCLEMAP_ELEVATION_V1"); out.writeInt(3)
            repeat(3) { out.writeShort(123) }
            for ((magic, text) in listOf("CYCLEMAP_ROADNAME_V1" to "国道2号", "CYCLEMAP_TURNLANES_V1" to "left|through")) {
                out.writeUTF(magic); out.writeInt(1); out.writeUTF(text); out.writeInt(3)
                repeat(3) { out.writeInt(0) }
            }
            if (count != null) {
                out.writeUTF("CYCLEMAP_BIKEINFO_V1"); out.writeInt(count); values.forEach(out::writeInt)
            }
        }
        return file to index
    }

    @Test fun readsRulesByOrdinalAndReservesZeroForNoInfo() {
        val tags = mapOf("highway" to "residential", "cycleway:left" to "lane", "sidewalk:right" to "yes")
        val forward = classifyBicycleRoad(tags, false)
        val reverse = classifyBicycleRoad(tags, true)
        val (file, index) = fixture(3, intArrayOf(forward.encode(), reverse.encode(), 0))
        LazyMappedRoadGraph.load(file, index).use { graph ->
            assertEquals(forward, graph.bicycleInfoOfEdge(0))
            assertEquals(reverse, graph.bicycleInfoOfEdge(1))
            assertNull(graph.bicycleInfoOfEdge(2))
            assertNull(graph.bicycleInfoOfEdge(-1)); assertNull(graph.bicycleInfoOfEdge(3))
            assertEquals("国道2号", graph.roadNameOfEdge(0))
            assertEquals("left|through", graph.turnLanesOfEdge(1))
        }
        LazyMappedRoadGraph.load(file, index, readRichSections = false).use { assertNull(it.bicycleInfoOfEdge(0)) }
    }

    @Test fun absentMismatchedAndTruncatedSectionsKeepEarlierSections() {
        for ((count, values) in listOf(null to intArrayOf(), 2 to intArrayOf(1, 2),
            -1 to intArrayOf(), Int.MAX_VALUE to intArrayOf(), 3 to intArrayOf(1, 2))) {
            val (file, index) = fixture(count, values)
            LazyMappedRoadGraph.load(file, index).use { graph ->
                repeat(3) { assertNull(graph.bicycleInfoOfEdge(it)) }
                assertEquals("国道2号", graph.roadNameOfEdge(0))
                assertEquals("left|through", graph.turnLanesOfEdge(0))
                assertEquals(12.3, graph.elevationMetersAt(0))
            }
        }
    }
}
