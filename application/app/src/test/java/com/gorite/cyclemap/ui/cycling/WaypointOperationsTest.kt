package com.gorite.cyclemap.ui.cycling

import org.junit.Assert.assertEquals
import org.junit.Test

class WaypointOperationsTest {
    private val items = listOf("A", "B", "C")

    @Test
    fun moveWaypointMovesItemsAndPreservesOrder() {
        assertEquals(listOf("B", "A", "C"), moveWaypoint(items, 0, 1))
        assertEquals(listOf("A", "C", "B"), moveWaypoint(items, 1, 1))
        assertEquals(listOf("B", "A", "C"), moveWaypoint(items, 1, -1))
    }

    @Test
    fun moveWaypointLeavesBoundaryAndOutOfRangeRequestsUnchanged() {
        assertEquals(items, moveWaypoint(items, 0, -1))
        assertEquals(items, moveWaypoint(items, 2, 1))
        assertEquals(items, moveWaypoint(items, -1, 1))
        assertEquals(items, moveWaypoint(items, 3, -1))
        assertEquals(items, moveWaypoint(items, 1, 0))
        assertEquals(items, moveWaypoint(items, 1, 4))
    }

    @Test
    fun removeWaypointAtRemovesValidIndex() {
        assertEquals(listOf("B", "C"), removeWaypointAt(items, 0))
        assertEquals(listOf("A", "C"), removeWaypointAt(items, 1))
        assertEquals(listOf("A", "B"), removeWaypointAt(items, 2))
    }

    @Test
    fun removeWaypointAtLeavesOutOfRangeRequestsUnchanged() {
        assertEquals(items, removeWaypointAt(items, -1))
        assertEquals(items, removeWaypointAt(items, 3))
        assertEquals(emptyList<String>(), removeWaypointAt(emptyList<String>(), 0))
    }
}
