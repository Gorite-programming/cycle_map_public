package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.InstructionType
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class NavigationHelperInstructionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class SynNode(val id: Long, val lat: Double, val lon: Double)

    private fun buildSyntheticGraph(
        nodes: List<SynNode>,
        edges: List<Pair<Long, Long>>,
    ): LazyMappedRoadGraph {
        val nodeById = nodes.associateBy { it.id }
        val indexById = nodes.withIndex().associate { it.value.id to it.index }
        val grouped = edges.groupBy { it.first }
        val graphFile = File(tempFolder.root, "curved-junction.graph")
        val indexFile = File(tempFolder.root, "curved-junction.graph.idx")
        val starts = LongArray(nodes.size)
        val firstOrdinals = IntArray(nodes.size)
        val counts = IntArray(nodes.size)
        val targets = ArrayList<Int>()

        DataOutputStream(BufferedOutputStream(FileOutputStream(graphFile))).use { out ->
            out.writeUTF("CYCLEMAP_GRAPH_V1")
            out.writeInt(nodes.size)
            for (node in nodes) {
                out.writeLong(node.id)
                out.writeDouble(node.lat)
                out.writeDouble(node.lon)
            }
            nodes.forEachIndexed { idx, node ->
                starts[idx] = out.size().toLong()
                firstOrdinals[idx] = targets.size
                val outgoing = grouped[node.id].orEmpty()
                counts[idx] = outgoing.size
                for ((_, toId) in outgoing) {
                    val to = nodeById.getValue(toId)
                    val dist = haversineMeters(node.lat, node.lon, to.lat, to.lon)
                    out.writeLong(node.id)
                    out.writeLong(toId)
                    out.writeDouble(dist)
                    out.writeUTF("residential")
                    out.writeByte(0)
                    out.writeByte(0)
                    targets += indexById.getValue(toId)
                }
            }
        }

        DataOutputStream(BufferedOutputStream(FileOutputStream(indexFile))).use { out ->
            out.writeUTF("CYCLEMAP_INDEX_V2")
            out.writeInt(nodes.size)
            starts.forEach { out.writeLong(it) }
            firstOrdinals.forEach { out.writeInt(it) }
            counts.forEach { out.writeInt(it) }
            out.writeInt(targets.size)
            targets.forEach { out.writeInt(it) }
        }
        return LazyMappedRoadGraph.load(graphFile, indexFile)
    }

    @Test
    fun buildRouteInstructions_identifiesCurvedTJunctionBranchByNodeId() {
        // 交差点 j の直後 c1 が約7.4mと短く c1->c2 がカーブするため、15〜30m先読み方位が
        // 直近エッジ方位と 2.5° 以上ずれるが、targetNodeId 指定により T_JUNCTION と判定されること。
        val nodes = listOf(
            SynNode(1L, 34.10100, 131.40000), // s
            SynNode(2L, 34.10000, 131.40000), // j
            SynNode(3L, 34.10000, 131.40008), // c1 (約7.4m東)
            SynNode(4L, 34.10012, 131.40020), // c2 (北東カーブ先)
            SynNode(5L, 34.10000, 131.39900), // d (西枝)
        )
        val edges = listOf(
            1L to 2L, 2L to 1L,
            2L to 3L, 3L to 2L,
            3L to 4L, 4L to 3L,
            2L to 5L, 5L to 2L,
        )
        buildSyntheticGraph(nodes, edges).use { graph ->
            val routeNodes = nodes.take(4)
            val instructions = buildRouteInstructions(
                graph = graph,
                nodeIds = routeNodes.map { it.id },
                coordinates = routeNodes.map { it.lat to it.lon },
            )
            val atJunction = instructions.first { it.nodeId == 2L }
            assertEquals(InstructionType.T_JUNCTION, atJunction.type)
            assertEquals("two-branches", atJunction.reason)
        }
    }
}
