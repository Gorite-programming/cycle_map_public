package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.routing.*
import com.gorite.cyclemap.utils.RouteSummary
import org.junit.Assert.*
import org.junit.Test

class DisplayedRouteDistanceTest {
    @Test fun trimmedEndpointSummaryEqualsDisplayedLineLengthWithoutDoubleCounting() {
        val coordinates = (0..10).map { 0.0 to it * 0.001 }
        val rawLength = coordinates.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }
        val raw = MappedRouteResult(nodeIds = (0..10).map { it.toLong() }, totalDistanceMeters = rawLength,
            totalCost = rawLength, coordinates = coordinates)
        val trimmed = trimRouteEnds(raw, 0.0001, 0.0005, 0.0001, 0.0095)
        assertEquals(2, trimmed.head.size)
        assertEquals(2, trimmed.tail.size)
        val line = trimmed.head + trimmed.result.coordinates + trimmed.tail
        val summary = RouteSummary(displayedRouteDistance(line), trimmed.result.nodeIds.size - 1)
        val expected = haversineMeters(0.0, 0.0005, 0.0, 0.0095) + 2 * haversineMeters(0.0, 0.0, 0.0001, 0.0)
        assertEquals(expected, summary.distanceMeters, 0.01)
        assertEquals(line.zipWithNext().sumOf { (a, b) -> haversineMeters(a.first, a.second, b.first, b.second) }, summary.distanceMeters, 1e-6)
        // EndpointTrim が落とした区間の長さを正しく引くようになったので (CORE-R1-05)、
        // トリム後の距離に端の接続線を足しても二重計上にならず、表示線の長さと一致する。
        val resultPlusConnectors = trimmed.result.totalDistanceMeters +
            displayedRouteDistance(trimmed.head + trimmed.result.coordinates.first()) +
            displayedRouteDistance(listOf(trimmed.result.coordinates.last()) + trimmed.tail)
        assertEquals(summary.distanceMeters, resultPlusConnectors, 1.0)
    }
}
