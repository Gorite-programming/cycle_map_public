package com.gorite.cyclemap.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaitingDestinationPolicyTest {
    @Test
    fun displayedRouteKeepsItsDestinationWhileWaitingForStart() {
        assertFalse(shouldShowWaitingDestination(hasDisplayedRoute = true))
    }

    @Test
    fun firstRouteShowsItsDestinationWhileWaitingForStart() {
        assertTrue(shouldShowWaitingDestination(hasDisplayedRoute = false))
    }
}
