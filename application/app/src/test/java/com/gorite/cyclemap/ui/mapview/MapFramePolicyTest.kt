package com.gorite.cyclemap.ui.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFramePolicyTest {
    @Test
    fun onlyNavigationFollowingHasAnFpsLimit() {
        for (powerSave in listOf(false, true)) {
            assertNull(mapMaxFps(navigationActive = false, followLocation = true, powerSave = powerSave))
            assertNull(mapMaxFps(navigationActive = false, followLocation = false, powerSave = powerSave))
            assertNull(mapMaxFps(navigationActive = true, followLocation = false, powerSave = powerSave))
        }
        assertEquals(30, mapMaxFps(navigationActive = true, followLocation = true, powerSave = false))
        assertEquals(20, mapMaxFps(navigationActive = true, followLocation = true, powerSave = true))
    }

    @Test
    fun frameAtTheMinimumIntervalIsDue() {
        assertEquals(0L, mapFrameIntervalNanos(null))
        assertEquals(33_333_333L, mapFrameIntervalNanos(30))
        assertEquals(50_000_000L, mapFrameIntervalNanos(20))

        val interval = mapFrameIntervalNanos(20)
        assertTrue(isMapFrameDue(null, 0L, interval))
        assertFalse(isMapFrameDue(0L, interval - 1, interval))
        assertTrue(isMapFrameDue(0L, interval, interval))
        assertTrue(isMapFrameDue(0L, interval + 1, interval))
    }

    @Test
    fun skipsFramesWithoutResettingTheInterval() {
        val frameTimes = (0..12).map { it * 1_000_000_000L / 120 }

        fun updatedFrames(maxFps: Int?): List<Int> {
            val interval = mapFrameIntervalNanos(maxFps)
            var lastUpdate: Long? = null
            return frameTimes.indices.filter { index ->
                val time = frameTimes[index]
                val due = isMapFrameDue(lastUpdate, time, interval)
                if (due) lastUpdate = time
                due
            }
        }

        assertEquals(frameTimes.indices.toList(), updatedFrames(null))
        assertEquals(listOf(0, 4, 8, 12), updatedFrames(30))
        assertEquals(listOf(0, 6, 12), updatedFrames(20))
    }
}
