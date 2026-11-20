package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.search.remainingRoutePoints
import com.gorite.cyclemap.utils.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSpotRouteSortTest {
    @Test
    fun preservesOrderWhenRouteHasFewerThanTwoPoints() {
        val spots = listOf(
            NearbySpot("A", "shop:convenience", 34.001, 132.001, 100.0),
            NearbySpot("B", "shop:convenience", 34.002, 132.002, 200.0),
        )
        val result = sortSpotsByRouteProximity(spots, listOf(RoutePoint(34.0, 132.0)))
        assertEquals(listOf("A", "B"), result.map { it.spot.name })
        assertNull(result[0].routeDistanceMeters)
        assertNull(result[1].routeDistanceMeters)
    }

    @Test
    fun sortsByDistanceToRouteDuringNavigation() {
        // 西から東へ伸びるルート (lat = 34.000, lon = 132.000 -> 132.050)
        val route = listOf(
            RoutePoint(34.000, 132.000),
            RoutePoint(34.000, 132.050),
        )
        // 現在地から近いがルートから南北に遠い店 (lat = 34.010 ≒ 1.1km 北)
        val offRouteClose = NearbySpot("ルート外", "shop:convenience", 34.010, 132.001, 200.0)
        // 現在地からは遠いがルート直上の店 (lat = 34.0002 ≒ 22m 北)
        val onRouteAhead = NearbySpot("ルート沿い", "shop:convenience", 34.0002, 132.020, 1800.0)

        val sorted = sortSpotsByRouteProximity(listOf(offRouteClose, onRouteAhead), route)
        assertEquals(listOf("ルート沿い", "ルート外"), sorted.map { it.spot.name })
        assertTrue((sorted[0].routeDistanceMeters ?: Double.MAX_VALUE) < 50.0)
        assertTrue((sorted[1].routeDistanceMeters ?: 0.0) > 900.0)
    }

    @Test
    fun formatsDistanceFromRouteInMetersAndKilometers() {
        assertEquals("ルートから85m", formatDistanceFromRoute(85.4))
        assertEquals("ルートから1.3km", formatDistanceFromRoute(1260.0))
        assertEquals("", formatDistanceFromRoute(Double.NaN))
    }

    @Test
    fun formatsDetourDistanceUsingRoundTripMeters() {
        assertEquals("迂回+171 m", formatDetourDistance(85.4))
        assertEquals("迂回+2.5 km", formatDetourDistance(1260.0))
        assertEquals("", formatDetourDistance(0.0))
        assertEquals("", formatDetourDistance(-10.0))
        assertEquals("", formatDetourDistance(Double.NaN))
    }

    @Test
    fun remainingRouteExcludesPassedSpotSoAheadSpotRanksFirst() {
        val p0 = RoutePoint(34.000, 132.000)
        val p1 = RoutePoint(34.000, 132.030)
        val p2 = RoutePoint(34.000, 132.060)
        val route = listOf(p0, p1, p2)

        val passedOnRoute = NearbySpot("通過済みの店", "shop:convenience", 34.000, 132.010, 2000.0)
        val aheadOffSlightly = NearbySpot("前方の店", "shop:convenience", 34.001, 132.045, 1500.0)

        // 全経路のまま並べると通過済みの店が「経路から0m」で先頭になる
        val fullSorted = sortSpotsByRouteProximity(listOf(passedOnRoute, aheadOffSlightly), route)
        assertEquals(listOf("通過済みの店", "前方の店"), fullSorted.map { it.spot.name })

        // p1 (132.030) まで進んだ残り経路で並べると前方の店が先頭になる
        val traveledToP1 = haversineMeters(p0.latitude, p0.longitude, p1.latitude, p1.longitude)
        val remaining = remainingRoutePoints(route, traveledToP1)
        val remainingSorted = sortSpotsByRouteProximity(listOf(passedOnRoute, aheadOffSlightly), remaining)
        assertEquals(listOf("前方の店", "通過済みの店"), remainingSorted.map { it.spot.name })
    }
}
