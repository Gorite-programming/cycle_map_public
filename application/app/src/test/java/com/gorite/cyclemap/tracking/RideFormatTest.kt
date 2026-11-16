package com.gorite.cyclemap.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class RideFormatTest {
    @Test
    fun speedHasOneDecimalPlace() {
        assertEquals("0.0", RideFormat.speed(0.0))
        assertEquals("18.0", RideFormat.speed(18.0))
        assertEquals("23.5", RideFormat.speed(23.46))
    }

    @Test
    fun distanceChangesUnitAtOneKilometer() {
        assertEquals("999 m", RideFormat.distance(999.0))
        assertEquals("1.00 km", RideFormat.distance(1000.0))
        assertEquals("12.35 km", RideFormat.distance(12345.0))
    }

    @Test
    fun durationHandlesMinuteAndHourBoundaries() {
        assertEquals("0:59", RideFormat.duration(59.0))
        assertEquals("1:00", RideFormat.duration(60.0))
        assertEquals("59:59", RideFormat.duration(3599.0))
        assertEquals("1:00:00", RideFormat.duration(3600.0))
        assertEquals("1:01:01", RideFormat.duration(3661.9))
    }

    @Test
    fun elevationGainRoundsMeters() {
        assertEquals("+0 m", RideFormat.elevationGain(0.0))
        assertEquals("+123 m", RideFormat.elevationGain(123.4))
        assertEquals("+124 m", RideFormat.elevationGain(123.5))
    }

    @Test
    fun negativeAndNonFiniteValuesBecomeZero() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertEquals("0.0", RideFormat.speed(it))
            assertEquals("0 m", RideFormat.distance(it))
            assertEquals("0:00", RideFormat.duration(it))
            assertEquals("+0 m", RideFormat.elevationGain(it))
        }
    }
}
