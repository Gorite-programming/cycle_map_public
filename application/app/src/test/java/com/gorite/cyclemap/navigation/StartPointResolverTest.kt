package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.utils.FALLBACK_LATITUDE
import com.gorite.cyclemap.utils.FALLBACK_LONGITUDE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.osmdroid.util.GeoPoint

class StartPointResolverTest {
    @Test
    fun prefersStartMarkerOverCurrentLocation() {
        val marker = GeoPoint(34.3977, 132.4753)
        val current = GeoPoint(34.1785, 131.4737)

        val resolved = resolveStartPoint(marker, current)

        assertEquals(marker, resolved)
    }

    @Test
    fun fallsBackToCurrentLocationWhenStartMarkerIsNull() {
        val current = GeoPoint(34.6618, 133.9344)

        val resolved = resolveStartPoint(null, current)

        assertEquals(current, resolved)
    }

    @Test
    fun returnsNullInsteadOfYamaguchiCityHallWhenBothAreMissing() {
        val resolved = resolveStartPoint(null, null)
        val resolvedFromCoords = resolveStartPoint(null, null, null)
        val resolvedFromLocation = resolveStartPointFromLocation(null, null)

        assertNull(resolved)
        assertNull(resolvedFromCoords)
        assertNull(resolvedFromLocation)

        val fallback = GeoPoint(FALLBACK_LATITUDE, FALLBACK_LONGITUDE)
        assertNotEquals(fallback, resolved)
    }

    @Test
    fun partialCoordinatesWithoutMarkerReturnNull() {
        assertNull(resolveStartPoint(null, 34.3977, null))
        assertNull(resolveStartPoint(null, null, 132.4753))

        val marker = GeoPoint(34.3977, 132.4753)
        assertEquals(marker, resolveStartPoint(marker, null, null))
    }
}
