package com.gorite.cyclemap.ui.home

import com.gorite.cyclemap.ui.screens.shouldPromptContinueRecordingOnNavigationEnd
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
        assertTrue(shouldPromptContinueRecordingOnNavigationEnd(hadActiveNavigation = true, isRecording = true))
        assertFalse(shouldPromptContinueRecordingOnNavigationEnd(hadActiveNavigation = true, isRecording = false))
        assertFalse(shouldPromptContinueRecordingOnNavigationEnd(hadActiveNavigation = false, isRecording = true))
        assertFalse(shouldPromptContinueRecordingOnNavigationEnd(hadActiveNavigation = false, isRecording = false))
    }
}
