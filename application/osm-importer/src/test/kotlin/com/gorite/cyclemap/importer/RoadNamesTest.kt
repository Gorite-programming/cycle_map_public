package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphEdge
import com.gorite.cyclemap.routing.GraphNode
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RoadGraph
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadNamesTest {
    @Test
    fun `国道・県道は番号で、ほかは名前で呼ぶ`() {
        assertEquals("国道2号", roadDisplayName(mapOf("highway" to "trunk", "ref" to "2", "name" to "国道2号")))
        assertEquals("国道183号", roadDisplayName(mapOf("highway" to "primary", "ref" to "183", "network" to "JP:national")))
        assertEquals("県道41号", roadDisplayName(mapOf("highway" to "primary", "ref" to "41", "name" to "広島湯来線")))
        assertEquals("県道250号", roadDisplayName(mapOf("highway" to "secondary", "ref" to "250", "network" to "JP:prefectural:hiroshima")))
        assertEquals("平和大通り", roadDisplayName(mapOf("highway" to "tertiary", "name" to "平和大通り")))
        assertEquals("県道30号", roadDisplayName(mapOf("highway" to "secondary_link", "ref" to "30;41")))
        assertNull(roadDisplayName(mapOf("highway" to "residential")))
        // 数字でない ref (路線記号など) は番号として扱わない
        assertEquals("x線", roadDisplayName(mapOf("highway" to "primary", "ref" to "E2", "name" to "x線")))
    }

    private fun graph(): RoadGraph {
        val a = GraphNode(1, 34.0, 132.0)
        val b = GraphNode(2, 34.0, 132.001)
        val c = GraphNode(3, 34.001, 132.001)
        return RoadGraph(
            linkedMapOf(1L to a, 2L to b, 3L to c),
            linkedMapOf(
                1L to mutableListOf(GraphEdge(1, 2, 92.0, "primary", roadName = "県道41号")),
                2L to mutableListOf(
                    GraphEdge(2, 1, 92.0, "primary", roadName = "県道41号"),
                    GraphEdge(2, 3, 111.0, "residential"),
                ),
                3L to mutableListOf(GraphEdge(3, 2, 111.0, "residential")),
            ),
        )
    }

    @Test
    fun `索引の道路名区間を書いて読める (標高区間と両方あっても)`() {
        val dir = Files.createTempDirectory("roadnames").toFile()
        val g = graph()
        val file = java.io.File(dir, "t.graph")
        val elevations = mapOf(1L to 10f, 2L to 12f, 3L to 20f)
        val writer = GraphBinaryWriter(elevations)
        writer.write(g, file)
        writeGraphIndex(file, java.io.File(dir, "t.graph.idx"), elevations, writer.edgeRoadNames)
        LazyMappedRoadGraph.load(file).use { lg ->
            assertTrue(lg.hasRoadNames)
            assertTrue(lg.hasElevations)
            val from2 = lg.outgoingBranches(lg.nodeIndexOf(2))
            assertEquals("県道41号", from2.single { it.targetIndex == lg.nodeIndexOf(1) }.roadName)
            assertNull(from2.single { it.targetIndex == lg.nodeIndexOf(3) }.roadName)
            assertEquals(12.0, lg.elevationMetersAt(lg.nodeIndexOf(2))!!, 0.01)
        }
    }

    @Test
    fun `道路名区間の無い旧索引も読める`() {
        val dir = Files.createTempDirectory("roadnames-old").toFile()
        val file = java.io.File(dir, "t.graph")
        GraphBinaryWriter().write(graph(), file)
        writeGraphIndex(file, java.io.File(dir, "t.graph.idx"))
        LazyMappedRoadGraph.load(file).use { lg ->
            assertFalse(lg.hasRoadNames)
            assertFalse(lg.hasElevations)
            assertNull(lg.outgoingBranches(0).first().roadName)
        }
    }

    @Test
    fun `車線は進行方向ごとに割り当てる`() {
        assertEquals("left|through" to null, turnLanesByDirection(mapOf("oneway" to "yes", "turn:lanes" to "left|through")))
        assertEquals(null to "left|through", turnLanesByDirection(mapOf("oneway" to "-1", "turn:lanes" to "left|through")))
        assertEquals("left|through" to "through|right",
            turnLanesByDirection(mapOf("turn:lanes:forward" to "left|through", "turn:lanes:backward" to "through|right")))
        // 両方向の道の、向きの無い turn:lanes は使わない
        assertEquals(null to null, turnLanesByDirection(mapOf("turn:lanes" to "left|through")))
    }

    @Test
    fun `車線区間を書いて読める`() {
        val dir = Files.createTempDirectory("lanes").toFile()
        val a = GraphNode(1, 34.0, 132.0)
        val b = GraphNode(2, 34.0, 132.001)
        val g = RoadGraph(
            linkedMapOf(1L to a, 2L to b),
            linkedMapOf(
                1L to mutableListOf(GraphEdge(1, 2, 92.0, "primary", true, turnLanes = "left|through|right")),
                2L to mutableListOf(),
            ),
        )
        val file = java.io.File(dir, "t.graph")
        val writer = GraphBinaryWriter()
        writer.write(g, file)
        writeGraphIndex(file, java.io.File(dir, "t.graph.idx"), null, writer.edgeRoadNames, writer.edgeTurnLanes)
        LazyMappedRoadGraph.load(file).use { lg ->
            assertEquals("left|through|right", lg.outgoingBranches(lg.nodeIndexOf(1)).single().turnLanes)
            assertNull(lg.outgoingBranches(lg.nodeIndexOf(1)).single().roadName)
        }
    }
}
