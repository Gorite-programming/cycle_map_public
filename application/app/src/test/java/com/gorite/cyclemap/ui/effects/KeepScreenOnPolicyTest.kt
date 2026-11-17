package com.gorite.cyclemap.ui.effects

import com.gorite.cyclemap.tracking.RideState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnPolicyTest {
    @Test
    fun keepsScreenOnWhenRunningOrIdle() {
        assertTrue(shouldKeepScreenOnInCycleComputer(RideState.RUNNING, pausedSinceMs = null, nowMs = 100_000L))
        assertTrue(shouldKeepScreenOnInCycleComputer(RideState.IDLE, pausedSinceMs = null, nowMs = 100_000L))
    }

    @Test
    fun keepsScreenOnWhilePausedUnder60Seconds() {
        assertTrue(shouldKeepScreenOnInCycleComputer(RideState.PAUSED, pausedSinceMs = 1_000L, nowMs = 60_999L))
    }

    @Test
    fun releasesScreenOnAfter60SecondsOfPause() {
        assertFalse(shouldKeepScreenOnInCycleComputer(RideState.PAUSED, pausedSinceMs = 1_000L, nowMs = 61_000L))
        assertFalse(shouldKeepScreenOnInCycleComputer(RideState.PAUSED, pausedSinceMs = 1_000L, nowMs = 120_000L))
    }
}
