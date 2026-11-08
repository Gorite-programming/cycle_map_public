package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
