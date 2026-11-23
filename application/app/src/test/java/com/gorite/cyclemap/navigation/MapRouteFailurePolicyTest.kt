package com.gorite.cyclemap.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRouteFailurePolicyTest {
    @Test
    fun missingDataWarningIsKeptWhenSwitchDoesNotStart() {
        val missingDataWarning = "岡山県のルートデータが端末に入っていません"
        var warning = missingDataWarning
        graphSwitchNotice("岡山県", isSwitchingGraph = false)?.let { warning = it }
        assertEquals(missingDataWarning, warning)

        graphSwitchNotice("岡山県", isSwitchingGraph = true)?.let { warning = it }
        assertEquals("岡山県のグラフに切替中です", warning)
    }

    @Test
    fun outOfMemoryBypassesCrossPlanningFallbackAndUsesMemoryWarning() {
        val oom = OutOfMemoryError("test")
        val failure = runCatching {
            crossPlanningFailure(oom, isCancelled = false)
        }.exceptionOrNull()

        assertSame(oom, failure)
        assertEquals(OUT_OF_MEMORY_ROUTE_MESSAGE, routeFailureMessage(failure is OutOfMemoryError))
    }

    @Test
    fun cancellationStillTakesPriorityAndOtherFailuresAllowFallback() {
        assertEquals(
            CrossPlanningOutcome.Cancelled,
            crossPlanningFailure(OutOfMemoryError(), isCancelled = true),
        )
        assertEquals(
            CrossPlanningOutcome.Failed,
            crossPlanningFailure(IllegalStateException(), isCancelled = false),
        )
    }

    @Test
    fun existingRouteStopsAreKeptUntilAReplacementIsApplied() {
        val previous = "目的地A" to listOf("経由地A")
        val requested = "目的地B" to listOf("経由地B")
        var displayed = previous
        if (shouldApplyManualRouteStops(hasExistingRoute = true, routeReplaced = false)) {
            displayed = requested
        }
        assertEquals(previous, displayed)

        if (shouldApplyManualRouteStops(hasExistingRoute = true, routeReplaced = true)) {
            displayed = requested
        }
        assertEquals(requested, displayed)
    }

    @Test
    fun firstRouteCanShowItsStopsBeforeAResult() {
        assertTrue(shouldApplyManualRouteStops(hasExistingRoute = false, routeReplaced = false))
        assertFalse(shouldApplyManualRouteStops(hasExistingRoute = false, routeReplaced = true))
    }
}
