package com.gorite.cyclemap.tracking
import org.junit.Assert.*
import org.junit.Test

class RideMotionPolicyTest {
    @Test fun accuracyHasAnInclusiveUpperLimit() {
        assertTrue(RideMotionPolicy.acceptsAccuracy(20f))
        listOf(20.01f, Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach {
            assertFalse(RideMotionPolicy.acceptsAccuracy(it))
        }
    }
    @Test fun lowSpeedAndSmallInferredMovementsAreStationary() {
        assertEquals(0.0, RideMotionPolicy.speedMps(0.499, 10.0, true), 0.0)
        assertEquals(0.5, RideMotionPolicy.speedMps(0.5, 3.0, true), 0.0)
        assertEquals(0.0, RideMotionPolicy.speedMps(2.0, 2.0, false), 0.0)
        assertEquals(3.0, RideMotionPolicy.speedMps(3.0, 3.0, false), 0.0)
        assertEquals(4.0, RideMotionPolicy.speedMps(4.0, 1.0, false, 4.0), 0.0)
        assertEquals(0.0, RideMotionPolicy.speedMps(0.4, 1.0, false, 4.0), 0.0)
        assertEquals(0.0, RideMotionPolicy.speedMps(Double.NaN, 3.0, true), 0.0)
    }
    @Test fun distanceRequiresBothMovementAndSpeed() {
        assertFalse(RideMotionPolicy.acceptsDistance(2.999, 5.0))
        assertFalse(RideMotionPolicy.acceptsDistance(20.0, 0.499))
        assertFalse(RideMotionPolicy.acceptsDistance(Double.NaN, 5.0))
        assertTrue(RideMotionPolicy.acceptsDistance(3.0, 0.5))
    }
}
