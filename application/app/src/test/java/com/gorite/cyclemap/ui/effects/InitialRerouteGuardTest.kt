package com.gorite.cyclemap.ui.effects

import com.gorite.cyclemap.routing.AdaptiveRerouteManager
import com.gorite.cyclemap.routing.RoutePoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialRerouteGuardTest {

    private fun runSimulation(
        routeFirst: RoutePoint,
        samples: List<Pair<RoutePoint, Double>>,
    ): Int = runBlocking {
        val manager = AdaptiveRerouteManager(
            scope = this,
            rerouteDistanceThreshold = 30.0,
            consecutiveSamples = 3,
            routerAction = { _, _ -> null },
        )
        manager.setInitialRoute(listOf(0L, 1L, 2L))
        val guard = InitialRerouteGuard()
        var rerouteRequests = 0
        var lastRerouteFrom: RoutePoint? = null

        for ((index, sample) in samples.withIndex()) {
            val (loc, distToRoute) = sample
            guard.onLocationFix(loc, distToRoute)
            manager.onLocationUpdate(
                distanceToRouteMeters = distToRoute,
                currentSpeedMps = 0f,
                currentNodeId = index.toLong(),
                goalNodeId = 2L,
            )
            manager.currentRerouteJob?.join()
            val movedEnough = lastRerouteFrom == null ||
                com.gorite.cyclemap.utils.haversineMeters(
                    lastRerouteFrom!!.latitude,
                    lastRerouteFrom!!.longitude,
                    loc.latitude,
                    loc.longitude,
                ) >= 30.0
            if (manager.state.value.isOffRoute && guard.shouldAllowReroute(loc, routeFirst) && movedEnough) {
                rerouteRequests++
                lastRerouteFrom = loc
            }
        }
        rerouteRequests
    }

    @Test
    fun stationaryFortyMetersFromRouteProducesZeroReroutesOverTenSamples() {
        val routeFirst = RoutePoint(34.3960, 132.4590)
        // 経路の最初の点から約 40m 離れた位置で静止
        val stationary = RoutePoint(34.39636, 132.4590)
        val samples = List(10) { stationary to 40.0 }

        val requests = runSimulation(routeFirst, samples)
        assertEquals(0, requests)
    }

    @Test
    fun movingFiftyMetersWhileStillOffRouteTriggersOneReroute() {
        val routeFirst = RoutePoint(34.3960, 132.4590)
        val start = RoutePoint(34.39636, 132.4590) // 40m from route
        // 開始位置から北へ約 55m 移動 (まだ経路から 95m 外れている)
        val moved = RoutePoint(34.39686, 132.4590)
        val samples = listOf(
            start to 40.0,
            start to 40.0,
            start to 40.0,
            moved to 95.0,
            moved to 95.0,
        )

        val requests = runSimulation(routeFirst, samples)
        assertEquals(1, requests)
    }

    @Test
    fun joiningRouteThenDeviatingFortyMetersTriggersReroute() {
        val routeFirst = RoutePoint(34.3960, 132.4590)
        val onRoute = RoutePoint(34.39605, 132.4590) // 5m on route
        val offRoute = RoutePoint(34.39641, 132.4590) // 40m off route
        val samples = listOf(
            onRoute to 5.0,
            offRoute to 40.0,
            offRoute to 40.0,
            offRoute to 40.0,
        )

        val requests = runSimulation(routeFirst, samples)
        assertEquals(1, requests)
    }

    @Test
    fun startingMoreThanTwoHundredMetersFromRouteFirstAllowsReroute() {
        val routeFirst = RoutePoint(34.3960, 132.4590)
        // 経路の最初の点から約 250m 離れた位置
        val farStart = RoutePoint(34.39825, 132.4590)
        val guard = InitialRerouteGuard()
        guard.onLocationFix(farStart, 250.0)
        assertFalse(guard.hasJoinedRoute)
        assertTrue(guard.shouldAllowReroute(farStart, routeFirst))
    }
}
