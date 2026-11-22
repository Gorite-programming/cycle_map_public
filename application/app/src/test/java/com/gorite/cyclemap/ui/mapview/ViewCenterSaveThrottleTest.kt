package com.gorite.cyclemap.ui.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewCenterSaveThrottleTest {
    @Test
    fun savesAtTheMinimumInterval() {
        val throttle = ViewCenterSaveThrottle()
        val start = 34.0 to 132.0
        val next = 34.1 to 132.0

        assertEquals(start, throttle.onCenterChanged(start, 1_000L))
        assertNull(throttle.onCenterChanged(next, 5_999L))
        assertEquals(next, throttle.onCenterChanged(next, 6_000L))
        assertNull(throttle.flush(6_001L))
    }

    @Test
    fun flushSavesOnlyTheLatestPendingCenter() {
        val throttle = ViewCenterSaveThrottle()
        val start = 34.0 to 132.0
        val latest = 34.2 to 132.0

        assertEquals(start, throttle.onCenterChanged(start, 1_000L))
        assertNull(throttle.onCenterChanged(34.1 to 132.0, 2_000L))
        assertNull(throttle.onCenterChanged(latest, 3_000L))
        assertEquals(latest, throttle.flush(3_100L))
        assertNull(throttle.flush(3_101L))
        assertNull(throttle.onCenterChanged(start, 6_000L))
    }

    @Test
    fun returningToTheSavedCenterClearsThePendingSave() {
        val throttle = ViewCenterSaveThrottle()
        val start = 34.0 to 132.0

        assertEquals(start, throttle.onCenterChanged(start, 1_000L))
        assertNull(throttle.onCenterChanged(34.1 to 132.0, 2_000L))
        assertNull(throttle.onCenterChanged(start, 3_000L))
        assertNull(throttle.flush(3_100L))
        assertNull(throttle.onCenterChanged(start, 6_000L))
    }
}
