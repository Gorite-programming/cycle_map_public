package com.gorite.cyclemap.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRerouteRaceTest {
    private val initialRoute = listOf(1L, 2L, 99L)

    /**
     * A blocking call with no suspension point in the router's coroutine. The nested
     * runBlocking has an independent Job, so cancelling the reroute cannot release it.
     * Deferred handshakes fix the ordering without sleeps or timed delays.
     */
    private class RouterGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        fun awaitRelease() {
            entered.complete(Unit)
            runBlocking { release.await() }
        }
    }

    private fun AdaptiveRerouteManager.deviate(nodeId: Long = 10L) {
        onLocationUpdate(40.0, 4.0f, nodeId, 99L)
    }

    @Test(timeout = 10_000)
    fun failedReroutePreservesRecoveryWhileCalculationIsPending() = runBlocking {
        val gate = RouterGate()
        val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
        val manager = AdaptiveRerouteManager(scope, consecutiveSamples = 1) { _, _ ->
            gate.awaitRelease()
            null
        }
        try {
            manager.setInitialRoute(initialRoute)
            manager.deviate()
            val job = requireNotNull(manager.currentRerouteJob)
            gate.entered.await()
            assertTrue(manager.state.value.isOffRoute)

            manager.onLocationUpdate(10.0, 4.0f, 10L, 99L)
            assertFalse(manager.state.value.isOffRoute)
            gate.release.complete(Unit)
            job.join()

            assertEquals(RerouteState(currentRouteNodeIds = initialRoute, rerouteAttemptCount = 1), manager.state.value)
        } finally {
            gate.release.complete(Unit)
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun staleSuccessCannotFinishNewRerouteOrResetItsDetector() = runBlocking {
        val oldGate = RouterGate()
        val newGate = RouterGate()
        val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
        val manager = AdaptiveRerouteManager(scope, consecutiveSamples = 1) { start, goal ->
            if (start == 10L) oldGate.awaitRelease() else newGate.awaitRelease()
            listOf(start, goal)
        }
        try {
            manager.setInitialRoute(initialRoute)
            manager.deviate()
            val oldJob = requireNotNull(manager.currentRerouteJob)
            oldGate.entered.await()
            manager.deviate(20L)
            val newJob = requireNotNull(manager.currentRerouteJob)
            newGate.entered.await()
            val pendingState = manager.state.value

            oldGate.release.complete(Unit)
            oldJob.join()
            assertTrue(oldJob.isCancelled)
            assertEquals(pendingState, manager.state.value)
            assertTrue(manager.currentRerouteJob === newJob)

            // At 20m the detector must remain off-route (recovery requires <=15m).
            // An obsolete detector.reset() would wrongly mark this as recovered.
            manager.onLocationUpdate(20.0, 4.0f, 20L, 99L)
            assertEquals(pendingState, manager.state.value)

            newGate.release.complete(Unit)
            newJob.join()
            assertEquals(RerouteState(currentRouteNodeIds = listOf(20L, 99L), rerouteAttemptCount = 2), manager.state.value)
        } finally {
            oldGate.release.complete(Unit)
            newGate.release.complete(Unit)
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun staleSuccessCannotOverwriteCompletedNewRoute() = runBlocking {
        val gate = RouterGate()
        val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
        val manager = AdaptiveRerouteManager(scope, consecutiveSamples = 1) { start, goal ->
            if (start == 10L) gate.awaitRelease()
            listOf(start, goal)
        }
        try {
            manager.setInitialRoute(initialRoute)
            manager.deviate()
            val oldJob = requireNotNull(manager.currentRerouteJob)
            gate.entered.await()
            manager.deviate(20L)
            requireNotNull(manager.currentRerouteJob).join()
            val latestState = manager.state.value
            assertEquals(listOf(20L, 99L), latestState.currentRouteNodeIds)

            gate.release.complete(Unit)
            oldJob.join()
            assertEquals(latestState, manager.state.value)
        } finally {
            gate.release.complete(Unit)
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun staleSuccessCannotOverwriteReset() = checkInvalidation(replaceRoute = false)

    @Test(timeout = 10_000)
    fun staleSuccessCannotOverwriteInitialRoute() = checkInvalidation(replaceRoute = true)

    private fun checkInvalidation(replaceRoute: Boolean) = runBlocking {
        val gate = RouterGate()
        val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
        val manager = AdaptiveRerouteManager(scope, consecutiveSamples = 1) { start, goal ->
            gate.awaitRelease()
            listOf(start, goal)
        }
        try {
            manager.setInitialRoute(initialRoute)
            manager.deviate()
            val oldJob = requireNotNull(manager.currentRerouteJob)
            gate.entered.await()
            val expectedRoute = if (replaceRoute) listOf(50L, 99L) else initialRoute
            if (replaceRoute) manager.setInitialRoute(expectedRoute) else manager.reset()
            val resetState = manager.state.value
            assertEquals(RerouteState(currentRouteNodeIds = expectedRoute, rerouteAttemptCount = 1), resetState)

            gate.release.complete(Unit)
            oldJob.join()
            assertTrue(oldJob.isCancelled)
            assertEquals(resetState, manager.state.value)
        } finally {
            gate.release.complete(Unit)
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun staleExceptionCannotFinishNewReroute() = runBlocking {
        val oldGate = RouterGate()
        val newGate = RouterGate()
        val scope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
        val manager = AdaptiveRerouteManager(scope, consecutiveSamples = 1) { start, goal ->
            if (start == 10L) {
                oldGate.awaitRelease()
                throw IllegalStateException("Obsolete calculation failed")
            }
            newGate.awaitRelease()
            listOf(start, goal)
        }
        try {
            manager.setInitialRoute(initialRoute)
            manager.deviate()
            val oldJob = requireNotNull(manager.currentRerouteJob)
            oldGate.entered.await()
            manager.deviate(20L)
            val newJob = requireNotNull(manager.currentRerouteJob)
            newGate.entered.await()
            val pendingState = manager.state.value
            oldGate.release.complete(Unit)
            oldJob.join()
            assertEquals(pendingState, manager.state.value)

            newGate.release.complete(Unit)
            newJob.join()
            assertEquals(listOf(20L, 99L), manager.state.value.currentRouteNodeIds)
            assertFalse(manager.state.value.isRerouting)
        } finally {
            oldGate.release.complete(Unit)
            newGate.release.complete(Unit)
            scope.cancel()
        }
    }

    @Test(timeout = 10_000)
    fun latestExceptionClearsRerouting() = runBlocking {
        val manager = AdaptiveRerouteManager(this, consecutiveSamples = 1) { _, _ ->
            throw IllegalStateException("Calculation failed")
        }
        manager.setInitialRoute(initialRoute)
        manager.deviate()
        requireNotNull(manager.currentRerouteJob).join()
        assertEquals(RerouteState(isOffRoute = true, currentRouteNodeIds = initialRoute, rerouteAttemptCount = 1), manager.state.value)
    }

    @Test(timeout = 10_000)
    fun cancellationExceptionIsRethrown() = runBlocking {
        val manager = AdaptiveRerouteManager(this, consecutiveSamples = 1) { _, _ ->
            throw CancellationException("Router cancelled")
        }
        manager.deviate()
        val job: Job = requireNotNull(manager.currentRerouteJob)
        job.join()
        assertTrue(job.isCancelled)
    }
}
