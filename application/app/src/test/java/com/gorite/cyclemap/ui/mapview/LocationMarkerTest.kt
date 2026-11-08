package com.gorite.cyclemap.ui.mapview

import com.gorite.cyclemap.tracking.GpsSignalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocationMarkerTest {
    @Test
    fun iconNameDependsOnModeAndGpsStatus() {
        assertEquals("cyclemap-nav-arrow-healthy", locationIconName("arrow", GpsSignalStatus.HEALTHY))
        assertEquals("cyclemap-nav-arrow-lost", locationIconName("arrow", GpsSignalStatus.LOST))
        assertEquals("cyclemap-location-beam-weak", locationIconName("beam", GpsSignalStatus.WEAK))
        assertEquals("cyclemap-location-dot-healthy", locationIconName("dot", GpsSignalStatus.HEALTHY))
    }

    @Test
    fun everyStatusHasItsOwnArrowImageName() {
        val names = GpsSignalStatus.entries.map { locationIconName("arrow", it) }.toSet()
        assertEquals(GpsSignalStatus.entries.size, names.size)
    }

    @Test
    fun arrowColorsDifferByStatusAndLeftIsLighterThanRight() {
        fun luminance(c: Int) = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
        for (status in GpsSignalStatus.entries) {
            val (left, right) = navigationArrowColors(status)
            // 左半分は明るく、右半分は濃く (立体感)。
            org.junit.Assert.assertTrue("$status", luminance(left) > luminance(right))
        }
        assertNotEquals(navigationArrowColors(GpsSignalStatus.HEALTHY), navigationArrowColors(GpsSignalStatus.LOST))
    }
}
