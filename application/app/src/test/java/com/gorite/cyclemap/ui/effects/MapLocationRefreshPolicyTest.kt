package com.gorite.cyclemap.ui.effects

import org.junit.Assert.*
import org.junit.Test

class MapLocationRefreshPolicyTest {
    private val origin = RefreshPosition(34.0, 131.0)
    private fun north(meters: Double) = origin.copy(latitude = origin.latitude + Math.toDegrees(meters / 6_371_000.0))

    @Test
    fun firstQueryAndCumulativeMovementUseLastQueryPosition() {
        assertTrue(shouldRefreshMapLocation(null, origin))
        for (meters in 0..49) assertFalse(shouldRefreshMapLocation(origin, north(meters.toDouble())))
        assertTrue(shouldRefreshMapLocation(origin, north(51.0)))
        assertFalse(shouldRefreshMapLocation(north(51.0), north(60.0)))
    }

    @Test
    fun stationaryGpsUpdatesSettleWithoutRestartingDelay() {
        assertFalse(shouldResolveAddress(origin, north(2.0), 0, 999))
        assertTrue(shouldResolveAddress(origin, north(2.0), 0, 1000))
        assertFalse(shouldRefreshMapLocation(origin, north(2.0)))
    }

    @Test
    fun movingFixesAreBoundedAndMissingSamplesWait() {
        assertFalse(shouldResolveAddress(null, origin, 0, 1000))
        assertFalse(shouldResolveAddress(origin, north(30.0), 0, 1000))
        assertFalse(shouldResolveAddress(origin, north(30.0), 0, 4999))
        assertTrue(shouldResolveAddress(origin, north(30.0), 0, 5000))
    }
}
