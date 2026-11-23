package com.gorite.cyclemap.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPermissionTest {
    @Test
    fun deniedPermissionNotifiesWithoutDispatchingRecording() {
        val calls = mutableListOf<String>()

        dispatchRecordingStart(
            hasLocationPermission = false,
            onDenied = { calls += "notify" },
            startService = { calls += "start" },
        )

        assertEquals(listOf("notify"), calls)
    }

    @Test
    fun grantedPermissionDispatchesRecordingWithoutNotifying() {
        val calls = mutableListOf<String>()

        dispatchRecordingStart(
            hasLocationPermission = true,
            onDenied = { calls += "notify" },
            startService = { calls += "start" },
        )

        assertEquals(listOf("start"), calls)
    }
}
