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

    private fun polylineLength(coords: List<Pair<Double, Double>>): Double =
        coords.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }

    @Test
    fun trimmedDistanceMatchesRemainingCoordinatesWhenStartTrimmed() {
        val coords = listOf(
            34.000 to 132.000,
            34.002 to 132.000,
            34.004 to 132.000,
            34.004 to 132.002,
        )
        val rawDist = polylineLength(coords)
        val raw = MappedRouteResult(
            nodeIds = listOf(1L, 2L, 3L, 4L),
            totalDistanceMeters = rawDist,
            totalCost = rawDist * 1.2,
            coordinates = coords,
            nodeIndices = listOf(0, 1, 2, 3),
        )
        // 起点が最初の辺の t ≈ 0.25 付近にある場合、node 1 を落とし node 2 (34.002) が新先頭になる。
        val t = trimRouteEnds(raw, 34.0005, 132.0001, 34.004, 132.002)
        assertEquals(listOf(2L, 3L, 4L), t.result.nodeIds)
        assertEquals(2, t.head.size)
        assertTrue(t.tail.isEmpty())
        val expectedRemaining = polylineLength(t.result.coordinates)
        assertEquals(expectedRemaining, t.result.totalDistanceMeters, 1.0)
        assertEquals(expectedRemaining * 1.2, t.result.totalCost, 1.0)
    }

    @Test
    fun trimmedDistanceMatchesRemainingCoordinatesWhenGoalTrimmed() {
        val coords = listOf(
            34.000 to 132.000,
            34.002 to 132.000,
            34.002 to 132.002,
            34.002 to 132.004,
        )
        val rawDist = polylineLength(coords)
        val raw = MappedRouteResult(
            nodeIds = listOf(1L, 2L, 3L, 4L),
            totalDistanceMeters = rawDist,
            totalCost = rawDist,
            coordinates = coords,
            nodeIndices = listOf(0, 1, 2, 3),
        )
        // 終点が最後の辺の途中 (辺末端から 30% 付近) にある場合、node 4 を落とし node 3 が新末尾になる。
        val t = trimRouteEnds(raw, 34.000, 132.000, 34.0021, 132.0034)
        assertEquals(listOf(1L, 2L, 3L), t.result.nodeIds)
        assertTrue(t.head.isEmpty())
        assertEquals(2, t.tail.size)
        assertEquals(polylineLength(t.result.coordinates), t.result.totalDistanceMeters, 1.0)
    }

    @Test
    fun trimmedDistanceMatchesRemainingCoordinatesWhenBothEndsTrimmed() {
        val coords = (0..6).map { 34.000 to (132.000 + it * 0.001) }
        val rawDist = polylineLength(coords)
        val raw = MappedRouteResult(
            nodeIds = coords.indices.map { it.toLong() + 100L },
            totalDistanceMeters = rawDist,
            totalCost = rawDist * 1.5,
            coordinates = coords,
            nodeIndices = coords.indices.toList(),
        )
        // 起点は最初の辺 (0→1) の 20%、終点は最後の辺 (5→6) の 80% 付近に射影される。
        val t = trimRouteEnds(raw, 34.0001, 132.0002, 34.0001, 132.0058)
        assertEquals(listOf(101L, 102L, 103L, 104L, 105L), t.result.nodeIds)
        assertEquals(2, t.head.size)
        assertEquals(2, t.tail.size)
        val remainingLength = polylineLength(t.result.coordinates)
        assertEquals(remainingLength, t.result.totalDistanceMeters, 1.0)
        assertEquals(remainingLength * 1.5, t.result.totalCost, 1.0)

        val fullDisplayedLine = t.head + t.result.coordinates + t.tail
        val joinedLength =
            polylineLength(t.head + listOf(t.result.coordinates.first())) +
                t.result.totalDistanceMeters +
                polylineLength(listOf(t.result.coordinates.last()) + t.tail)
        assertEquals(polylineLength(fullDisplayedLine), joinedLength, 1.0)
    }
}
