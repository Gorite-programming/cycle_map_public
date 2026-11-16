package com.gorite.cyclemap.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndpointTrimTest {
    // 南北に走る道: n0(34.000) → n1(34.002) → n2(34.002, 東 0.002)。1 つめの辺は約 222 m。
    private fun route() = MappedRouteResult(
        nodeIds = listOf(10L, 11L, 12L),
        totalDistanceMeters = 222.0 + 184.0,
        totalCost = 406.0,
        coordinates = listOf(34.000 to 132.000, 34.002 to 132.000, 34.002 to 132.002),
        nodeIndices = listOf(0, 1, 2),
    )

    @Test
    fun startInMiddleOfFirstEdgeDropsBacktrack() {
        // 起点は辺の中ほど (34.001) の少し東。最寄りノードは n0 だが、辺へ射影すれば n0 まで戻らなくてよい。
        val t = trimRouteEnds(route(), 34.0011, 132.0001, 34.002, 132.002)
        assertEquals(listOf(11L, 12L), t.result.nodeIds)
        assertEquals(listOf(1, 2), t.result.nodeIndices)
        assertEquals(2, t.result.coordinates.size)
        assertEquals(34.0011 to 132.0001, t.head[0])
        assertTrue(t.result.totalDistanceMeters < 406.0 - 100.0)
        assertTrue(t.tail.isEmpty())
    }

    @Test
    fun goalBesideLastEdgeEndsAtProjection() {
        // 目的地は最後の辺 (n1→n2) の中ほどの北側。n2 まで行かずに辺の途中で終わる。
        val t = trimRouteEnds(route(), 34.000, 132.000, 34.0021, 132.001)
        assertEquals(listOf(10L, 11L), t.result.nodeIds)
        assertEquals(34.0021 to 132.001, t.tail.last())
        assertTrue(t.result.totalDistanceMeters < 406.0 - 50.0)
    }

    @Test
    fun noChangeWhenEndpointsAreAtNodes() {
        val r = route()
        val t = trimRouteEnds(r, 34.000, 132.000, 34.002, 132.002)
        assertEquals(r, t.result)
        assertTrue(t.head.isEmpty() && t.tail.isEmpty())
    }

    @Test
    fun noChangeWhenStartIsBehindTheFirstNode() {
        // 起点が n0 の手前 (南) にいるときは、射影が端 (t=0) に落ちるので触らない。
        val r = route()
        val t = trimRouteEnds(r, 33.9995, 132.0, 34.002, 132.002)
        assertEquals(r.nodeIds, t.result.nodeIds)
    }

    @Test
    fun shortRoutesAreLeftAlone() {
        val r = route().copy(nodeIds = listOf(10L, 11L), coordinates = listOf(34.000 to 132.0, 34.002 to 132.0), nodeIndices = listOf(0, 1))
        assertEquals(r, trimRouteEnds(r, 34.001, 132.0001, 34.002, 132.0).result)
    }

    @Test
    fun nodeIndicesStayInSync() {
        val t = trimRouteEnds(route(), 34.0011, 132.0001, 34.0, 132.0)
        assertEquals(t.result.nodeIds.size, t.result.nodeIndices.size)
        assertEquals(t.result.nodeIds.size, t.result.coordinates.size)
    }

    @Test
    fun startPassedLaterDuringADetourSkipsTheDetour() {
        // n0 から南へ行って折り返し、北上する。起点は北上する道の途中 (34.001) の脇。
        val r = MappedRouteResult(
            nodeIds = listOf(10L, 11L, 12L, 13L),
            totalDistanceMeters = 111.0 + 334.0 + 223.0,
            totalCost = 700.0,
            coordinates = listOf(34.000 to 132.000, 33.999 to 132.000, 34.002 to 132.000, 34.004 to 132.000),
            nodeIndices = listOf(0, 1, 2, 3),
        )
        val t = trimRouteEnds(r, 34.001, 132.0001, 34.004, 132.000)
        assertEquals(listOf(12L, 13L), t.result.nodeIds)
        assertTrue(t.head.size == 2)
        assertTrue(t.result.totalDistanceMeters < 340.0)
    }

    @Test
    fun goalPassedEarlierThenLoopingBackEndsWhereItPassesTheGoal() {
        // 終点 (34.001 の脇) を通り過ぎて 34.002 まで行き、少し戻って終わるルート。
        val r = MappedRouteResult(
            nodeIds = listOf(9L, 10L, 11L, 12L, 13L),
            totalDistanceMeters = 111.0 + 278.0 + 55.0 + 72.0,
            totalCost = 800.0,
            coordinates = listOf(33.997 to 132.000, 33.999 to 132.000, 34.0015 to 132.000, 34.002 to 132.000, 34.0015 to 132.0005),
            nodeIndices = listOf(0, 1, 2, 3, 4),
        )
        val t = trimRouteEnds(r, 33.997, 132.000, 34.001, 132.0001)
        assertTrue(t.result.nodeIds.size < 5)
        assertEquals(34.001 to 132.0001, t.tail.last())
    }
}
