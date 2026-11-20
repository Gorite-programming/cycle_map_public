package com.gorite.cyclemap.tracking

import org.junit.Assert.*
import org.junit.Test

class RideRecordingRestorePolicyTest {
    private val savedAt = 1_010_000L
    private val rideId = "c7119179-4491-4fe5-951f-a6ed6d029c71"

    private fun session() = RideSession().apply {
        for (i in 0..10) {
            onLocation(1_000_000L + i * 1000, 34.36, 132.40 + i * 5e-5, 5f, 5f,
                altitudeM = 100.0 + i * 5)
        }
    }

    @Test
    fun onlyExactNonemptyIdsMatch() {
        assertTrue(RideRecordingRestorePolicy.matches(rideId, rideId))
        assertFalse(RideRecordingRestorePolicy.matches(rideId, "another-journal"))
        for (invalid in listOf(null, "", " ", "\n", "ride\nSTART", " ride", "ride\tID")) {
            assertFalse(RideRecordingRestorePolicy.matches(invalid, invalid))
            assertFalse(RideRecordingRestorePolicy.matches(rideId, invalid))
            assertFalse(RideRecordingRestorePolicy.matches(invalid, rideId))
        }
    }

    @Test
    fun matchingRecordingRestoresStatisticsPausedWithoutCountingDowntime() {
        val original = session()
        val before = original.snapshot(savedAt)
        val text = RideRecordingRestorePolicy.encode(original, rideId, savedAt)
        val now = savedAt + 60_000
        val restored = RideRecordingRestorePolicy.decideRestore(text, rideId, now)!!
        val after = restored.snapshot(now)
        assertEquals(RideState.PAUSED, after.state)
        assertTrue(before.distanceM > 0)
        assertTrue(before.elevationGainM > 0)
        assertEquals(before.distanceM, after.distanceM, 0.0)
        assertEquals(before.movingTimeS, after.movingTimeS, 0.0)
        assertEquals(before.maxKmh, after.maxKmh, 0.0)
        assertEquals(before.elevationGainM, after.elevationGainM, 0.0)
        // 復旧地点までのジャンプと停止中の距離起点は持ち越さない。
        assertEquals("", restored.toState()["distanceOrigin"])
        restored.start(now)
        restored.onLocation(now, 35.0, 133.0, 5f, 5f)
        assertEquals(before.distanceM, restored.snapshot(now).distanceM, 0.0)
    }

    @Test
    fun differentMissingOrLegacyIdNeverRestoresEvenWhenRecent() {
        val original = session()
        val text = RideRecordingRestorePolicy.encode(original, rideId, savedAt)
        assertNull(RideRecordingRestorePolicy.decideRestore(text, "another-journal", savedAt))
        assertNull(RideRecordingRestorePolicy.decideRestore(text, null, savedAt))
        val legacy = RideSessionStore.encode(original.toState(), savedAt)
        assertNull(RideRecordingRestorePolicy.decideRestore(legacy, rideId, savedAt))
        // ID のない既存の復元処理は変更しない。
        assertNotNull(RideSessionStore.decideRestore(legacy, savedAt))
    }

    @Test
    fun matchingIdStillRequiresRecentValidStartedSession() {
        val text = RideRecordingRestorePolicy.encode(session(), rideId, savedAt)
        assertNotNull(RideRecordingRestorePolicy.decideRestore(text, rideId,
            savedAt + RideSessionStore.MAX_AGE_MS))
        assertNull(RideRecordingRestorePolicy.decideRestore(text, rideId,
            savedAt + RideSessionStore.MAX_AGE_MS + 1))
        assertNull(RideRecordingRestorePolicy.decideRestore(text, rideId, savedAt - 1))
        assertNull(RideRecordingRestorePolicy.decideRestore(
            text.replace("distanceM=", "distanceM=broken"), rideId, savedAt))
        assertNull(RideRecordingRestorePolicy.decideRestore("garbage", rideId, savedAt))
        assertNull(RideRecordingRestorePolicy.decideRestore(
            RideRecordingRestorePolicy.encode(RideSession(), rideId, savedAt), rideId, savedAt))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotEncodeIdThatWouldInjectAnotherStateLine() {
        RideRecordingRestorePolicy.encode(session(), "ride\nrecordingId=another", savedAt)
    }
}
