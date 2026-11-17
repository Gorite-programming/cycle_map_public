package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.utils.RouteSummary
import com.gorite.cyclemap.routing.NavigationStats
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.tracking.RideSnapshot
import com.gorite.cyclemap.tracking.RideState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrivalSummaryTest {
    @Test
    fun prefersRideSnapshotWhenAvailable() {
        val snapshot = RideSnapshot(
            state = RideState.RUNNING,
            speedKmh = 18.0,
            distanceM = 12_340.0,
            movingTimeS = 2_400.0,
            elapsedS = 2_600.0,
            averageKmh = 18.5,
            maxKmh = 28.0,
            elevationGainM = 80.0,
        )
        val summary = buildArrivalSummary(
            rideSnapshot = snapshot,
            navStats = NavigationStats(0.0, 2600.0, 5.0, 0.0, null, true),
            navigationProgress = RouteProgress(12_000.0, 12_000.0, 0.0, null, null),
            routeSummary = RouteSummary(12_000.0, 100),
        )
        assertEquals(12_340.0, summary.distanceMeters!!, 1e-6)
        assertEquals(2_400.0, summary.durationSeconds!!, 1e-6)
        assertEquals(18.5, summary.averageSpeedKmh!!, 1e-6)
    }

    @Test
    fun fallsBackToProgressAndNavStatsWithoutRideSnapshot() {
        val summary = buildArrivalSummary(
            rideSnapshot = null,
            navStats = NavigationStats(0.0, 1_800.0, 5.0, 0.0, null, true),
            navigationProgress = RouteProgress(9_000.0, 9_020.0, 0.0, null, null),
            routeSummary = RouteSummary(9_020.0, 50),
        )
        assertEquals(9_000.0, summary.distanceMeters!!, 1e-6)
        assertEquals(1_800.0, summary.durationSeconds!!, 1e-6)
        assertEquals(18.0, summary.averageSpeedKmh!!, 1e-6)
    }

    @Test
    fun omitsFakeAverageSpeedWhenElapsedIsTooShortOrMissing() {
        val summary = buildArrivalSummary(
            rideSnapshot = null,
            navStats = NavigationStats(0.0, 3.0, 4.16, 0.0, null, true),
            navigationProgress = null,
            routeSummary = RouteSummary(500.0, 10),
        )
        assertEquals(500.0, summary.distanceMeters!!, 1e-6)
        assertEquals(3.0, summary.durationSeconds!!, 1e-6)
        assertNull(summary.averageSpeedKmh)
    }

    @Test
    fun formatsArrivalDurationForSecondsMinutesAndHours() {
        assertEquals("45秒", formatArrivalDuration(45.0))
        assertEquals("12分", formatArrivalDuration(720.0))
        assertEquals("1時間15分", formatArrivalDuration(4500.0))
        assertEquals("--", formatArrivalDuration(0.0))
    }

    @Test
    fun ignoresImplausibleRideSnapshot() {
        // 模擬位置の瞬間移動などで、1 秒に 275m 進んだ記録 (平均 990km/h) は使わず、ナビの値に戻す
        val snapshot = RideSnapshot(RideState.RUNNING, 0.0, 275.0, 1.0, 1.0, 990.0, 990.0, 0.0)
        val summary = buildArrivalSummary(
            rideSnapshot = snapshot,
            navStats = NavigationStats(0.0, 120.0, 5.0, 0.0, null, true),
            navigationProgress = RouteProgress(377.0, 377.0, 0.0, null, null),
            routeSummary = RouteSummary(377.0, 10),
        )
        assertEquals(377.0, summary.distanceMeters!!, 1e-6)
        assertEquals(120.0, summary.durationSeconds!!, 1e-6)
        assertEquals(11.31, summary.averageSpeedKmh!!, 0.01)
    }
}
