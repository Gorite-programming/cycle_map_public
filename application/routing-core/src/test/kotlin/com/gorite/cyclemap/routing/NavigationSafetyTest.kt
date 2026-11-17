package com.gorite.cyclemap.routing

import kotlin.test.*

class NavigationSafetyTest {
    private fun nearEnd(offRoute: Double = 5.0) = RouteProgress(990.0, 1000.0, offRoute, null, null)

    @Test fun arrivalNeedsTwoDistinctAccurateFixesNearTheDestinationAndRoute() {
        val detector = ArrivalDetector()
        assertFalse(detector.update(nearEnd(), 10.0, 5.0, 1))
        assertFalse(detector.update(nearEnd(), 10.0, 5.0, 1))
        assertTrue(detector.update(nearEnd(), 10.0, 5.0, 2))
        assertFalse(detector.update(nearEnd(), 100.0, 5.0, 3))
        assertFalse(detector.update(nearEnd(), 10.0, 5.0, 4))
        assertFalse(detector.update(nearEnd(90.0), 10.0, 5.0, 5))
        assertFalse(detector.update(nearEnd(), 10.0, 80.0, 6))
        assertFalse(detector.update(nearEnd(), 10.0, Double.NaN, 7))
        assertFalse(detector.update(nearEnd(), 10.0, 5.0, 8))
        assertTrue(detector.update(nearEnd(), 10.0, 5.0, 9))
    }

    @Test fun arrivalRadiusExpandsWithAccuracyButNeverPastFiftyMeters() {
        val detector = ArrivalDetector()
        assertFalse(detector.update(nearEnd(), 40.0, 40.0, 1))
        assertTrue(detector.update(nearEnd(), 40.0, 40.0, 2))
        assertFalse(detector.update(nearEnd(), 51.0, 50.0, 3))
    }

    @Test fun remainingDistanceAloneDoesNotConfirmArrival() {
        assertFalse(computeNavigationStats(nearEnd(), 4.0, 0, 1000, 1000)!!.isArrived)
    }

    @Test fun outAndBackProgressStaysOnReturnLeg() {
        val index = RouteProgressIndex(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01), RoutePoint(0.0, 0.0)))
        val progress = index.calculate(RoutePoint(0.0, 0.0049), previousProgressMeters = 1667.0,
            possibleTravelMeters = 20.0, bearingDegrees = 270.0)!!
        assertTrue(progress.distanceFromStartMeters > 1600.0)
        assertTrue(progress.distanceToRouteMeters < 1.0)
    }

    @Test fun headingDisambiguatesOverlappingSegmentsOnFirstFix() {
        val index = RouteProgressIndex(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.01), RoutePoint(0.0, 0.0)))
        assertTrue(index.calculate(RoutePoint(0.0, 0.005), bearingDegrees = 270.0)!!.distanceFromStartMeters > 1500.0)
        assertTrue(index.calculate(RoutePoint(0.0, 0.005), bearingDegrees = 90.0)!!.distanceFromStartMeters < 600.0)
    }

    @Test fun crossingDoesNotJumpToLaterVisit() {
        val route = listOf(RoutePoint(0.0, -0.01), RoutePoint(0.0, 0.01), RoutePoint(-0.01, 0.0), RoutePoint(0.01, 0.0))
        val progress = RouteProgressIndex(route).calculate(RoutePoint(0.00001, 0.0),
            previousProgressMeters = 1100.0, possibleTravelMeters = 20.0, bearingDegrees = 90.0)!!
        assertTrue(progress.distanceFromStartMeters in 1100.0..1130.0)
    }

    @Test fun farAwayLocationCanReacquireOutsideThePreviousWindow() {
        val index = RouteProgressIndex((0..100).map { RoutePoint(0.0, it * 0.001) })
        val progress = index.calculate(RoutePoint(0.0, 0.09), previousProgressMeters = 0.0)!!
        assertTrue(progress.distanceFromStartMeters > 9000.0)
        assertTrue(progress.distanceToRouteMeters < 1.0)
    }

    @Test fun cachedLongRouteMatchesBaselineAndIsIndependentOfInputMutation() {
        val route = (0..10000).map { RoutePoint(0.0, it * 0.00001) }.toMutableList()
        val index = RouteProgressIndex(route)
        val expected = calculateRouteProgress(RoutePoint(0.0, 0.05), route)!!
        route.clear()
        repeat(100) {
            val actual = index.calculate(RoutePoint(0.0, 0.05), previousProgressMeters = expected.distanceFromStartMeters)!!
            assertEquals(expected.distanceFromStartMeters, actual.distanceFromStartMeters, 0.001)
            assertEquals(expected.routeDistanceMeters, actual.routeDistanceMeters, 0.001)
        }
        assertEquals(0.05, index.pointAt(expected.distanceFromStartMeters)!!.first.longitude, 1e-9)
    }

    @Test fun cachedDeadReckoningMatchesExistingRouteAdvance() {
        val route = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.001), RoutePoint(0.001, 0.001))
        val expected = DeadReckoner.estimate(route.first(), 90.0, 5.0, 10.0, route, 90.0)
        val actual = RouteProgressIndex(route).pointAt(140.0)!!
        assertEquals(expected.location.latitude, actual.first.latitude, 1e-9)
        assertEquals(expected.location.longitude, actual.first.longitude, 1e-9)
        assertEquals(expected.bearingDegrees, actual.second!!, 1e-9)
    }
}
