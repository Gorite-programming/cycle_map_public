package com.gorite.cyclemap.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEndPromptTest {
    @Test
    fun replacingTheRouteClearsThePreviousNavigation() {
        val tracker = NavigationEndPromptTracker()
        tracker.onNavigationStarted()
        tracker.onRouteReplaced()
        assertFalse(tracker.shouldPromptOnClear(isRecording = true))
    }

    @Test
    fun reroutingKeepsTheCurrentNavigation() {
        val tracker = NavigationEndPromptTracker()
        tracker.onNavigationStarted()
        tracker.onRouteReplaced(isReroute = true)
        assertTrue(tracker.shouldPromptOnClear(isRecording = true))
    }

    @Test
    fun clearingTheRouteConsumesThePrompt() {
        val tracker = NavigationEndPromptTracker()
        tracker.onNavigationStarted()
        assertTrue(tracker.shouldPromptOnClear(isRecording = true))
        assertFalse(tracker.shouldPromptOnClear(isRecording = true))
    }

    @Test
    fun restoringTheSavedFlagKeepsThePromptForTheSameRoute() {
        val tracker = NavigationEndPromptTracker()
        tracker.onNavigationStarted()
        val restored = NavigationEndPromptTracker(tracker.didStartNavigation)
        assertTrue(restored.shouldPromptOnClear(isRecording = true))
    }
}
