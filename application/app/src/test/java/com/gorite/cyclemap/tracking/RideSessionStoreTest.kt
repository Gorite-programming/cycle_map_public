package com.gorite.cyclemap.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideSessionStoreTest {
    private fun runningSession(): RideSession {
        val s = RideSession()
        var t = 1_000_000L
        for (i in 0 until 30) { s.onLocation(t, 34.36, 132.40 + i * 5e-5, 5f, 5f); t += 1000 }
        return s
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val state = runningSession().toState()
        val map = RideSessionStore.decode(RideSessionStore.encode(state, 123L))!!
        assertEquals(state, map.filterKeys { it != "savedAtMs" })
        assertEquals("123", map["savedAtMs"])
    }

    @Test
    fun recentRunIsRestoredAsPausedWithItsValues() {
        val s = runningSession()
        val before = s.snapshot(1_030_000L)
        val text = RideSessionStore.encode(s.toState(), 1_030_000L)
        val restored = RideSessionStore.decideRestore(text, 1_030_000L + 5 * 60_000L)
        assertNotNull(restored)
        val snap = restored!!.snapshot(1_030_000L + 5 * 60_000L)
        assertEquals(RideState.PAUSED, snap.state)
        assertEquals(before.distanceM, snap.distanceM, 1e-6)
        assertTrue(snap.distanceM > 100.0)
    }

    @Test
    fun oldOrFutureOrBrokenRunsAreDropped() {
        val s = runningSession()
        val text = RideSessionStore.encode(s.toState(), 1_000_000L)
        assertNull(RideSessionStore.decideRestore(text, 1_000_000L + RideSessionStore.MAX_AGE_MS + 1))
        assertNull(RideSessionStore.decideRestore(text, 999_000L))
        assertNull(RideSessionStore.decideRestore("garbage without equals", 1_000_000L))
        assertNull(RideSessionStore.decideRestore(text.replace("distanceM=", "distanceM=x"), 1_000_000L))
        assertNull(RideSessionStore.decideRestore("", 1_000_000L))
    }

    @Test
    fun aRunThatNeverStartedIsNotRestored() {
        val text = RideSessionStore.encode(RideSession().toState(), 1_000_000L)
        assertNull(RideSessionStore.decideRestore(text, 1_000_000L))
    }
}
