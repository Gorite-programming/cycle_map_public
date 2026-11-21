package com.gorite.cyclemap.ui.home

import com.gorite.cyclemap.ui.screens.NavigationEndPromptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDashboardTest {

    @Test
    fun freeRideRecordingHintIsExplainedToUser() {
        assertEquals("押すと記録が始まります", HOME_FREE_RIDE_RECORDING_HINT)
    }

    @Test
    fun navigationEndPromptsContinueRecordingOnlyWhenNavigatingAndRecording() {
        val tracker = NavigationEndPromptTracker()
        assertFalse(tracker.shouldPromptOnClear(isRecording = true))
        assertFalse(tracker.shouldPromptOnClear(isRecording = false))

        tracker.onNavigationStarted()
        assertTrue(tracker.shouldPromptOnClear(isRecording = true))

        tracker.onNavigationStarted()
        assertFalse(tracker.shouldPromptOnClear(isRecording = false))
        assertFalse(tracker.shouldPromptOnClear(isRecording = true))
    }
}
