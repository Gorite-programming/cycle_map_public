package com.gorite.cyclemap.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextHazardTest {

    @Test
    fun nextHazardAhead_emptyListReturnsNull() {
        assertNull(nextHazardAhead(emptyList(), 0.0))
    }

    @Test
    fun nextHazardAhead_crossing500mBoundary() {
        val hazards = listOf(RouteHazard(HazardKind.CROSSING, null, 1000.0, 1000.0))

        // 501 m 手前 -> 出さない
        assertNull(nextHazardAhead(hazards, 499.0))

        // ちょうど 500 m 手前 -> 出す
        val at500 = nextHazardAhead(hazards, 500.0)
        assertNotNull(at500)
        at500!!
        assertEquals(HazardKind.CROSSING, at500.kind)
        assertNull(at500.name)
        assertEquals(500.0, at500.distanceMeters, 1e-6)
        assertFalse(at500.inside)
        assertEquals(0.0, at500.remainingMeters, 1e-6)
    }

    @Test
    fun nextHazardAhead_bridge1000mBoundaryAndInsideBridge() {
        val hazards = listOf(RouteHazard(HazardKind.BRIDGE, "錦帯橋", 1500.0, 1700.0))

        // 1001 m 手前 -> 出さない
        assertNull(nextHazardAhead(hazards, 499.0))

        // ちょうど 1000 m 手前 -> 出す
        val at1000 = nextHazardAhead(hazards, 500.0)
        assertNotNull(at1000)
        assertEquals(HazardKind.BRIDGE, at1000!!.kind)
        assertEquals("錦帯橋", at1000.name)
        assertEquals(1000.0, at1000.distanceMeters, 1e-6)
        assertFalse(at1000.inside)

        // 橋の入口ちょうど -> inside = true, remainingMeters = 200.0
        val atStart = nextHazardAhead(hazards, 1500.0)
        assertNotNull(atStart)
        assertTrue(atStart!!.inside)
        assertEquals(0.0, atStart.distanceMeters, 1e-6)
        assertEquals(200.0, atStart.remainingMeters, 1e-6)

        // 橋の途中 -> inside = true, remainingMeters = 120.0
        val onBridge = nextHazardAhead(hazards, 1580.0)
        assertNotNull(onBridge)
        assertTrue(onBridge!!.inside)
        assertEquals("錦帯橋", onBridge.name)
        assertEquals(0.0, onBridge.distanceMeters, 1e-6)
        assertEquals(120.0, onBridge.remainingMeters, 1e-6)

        // 橋の終点ちょうど -> 通り終えたので null
        assertNull(nextHazardAhead(hazards, 1700.0))
    }

    @Test
    fun nextHazardAhead_ignoresPassedAndPicksNearestCrossingBeforeBridge() {
        val hazards = listOf(
            RouteHazard(HazardKind.CROSSING, null, 200.0, 200.0),
            RouteHazard(HazardKind.CROSSING, null, 800.0, 800.0),
            RouteHazard(HazardKind.BRIDGE, "錦帯橋", 900.0, 1100.0),
        )

        // 200 m の踏切は通り過ぎ済み。手前の 800 m の踏切が 900 m の橋より先に返る
        val result = nextHazardAhead(hazards, 350.0)
        assertNotNull(result)
        assertEquals(HazardKind.CROSSING, result!!.kind)
        assertEquals(450.0, result.distanceMeters, 1e-6)

        // 先頭が 501 m 先の踏切、その奥が 600 m 先の橋でも「最初の未通過地点」の条件で見る
        val blockedByCrossing = listOf(
            RouteHazard(HazardKind.CROSSING, null, 801.0, 801.0),
            RouteHazard(HazardKind.BRIDGE, "奥の橋", 900.0, 1100.0),
        )
        assertNull(nextHazardAhead(blockedByCrossing, 300.0))
    }
}
