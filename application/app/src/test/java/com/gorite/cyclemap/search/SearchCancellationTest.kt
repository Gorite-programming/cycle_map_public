package com.gorite.cyclemap.search

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SearchCancellationTest {
    @Test fun supersededInputDoesNotStartQueryDuringDebounce() = runBlocking {
        val ran = AtomicBoolean(false)
        val old = launch(start = CoroutineStart.UNDISPATCHED) { debouncedSearch({}) { ran.set(true) } }
        old.cancelAndJoin()
        assertFalse(ran.get())
        val started = System.nanoTime()
        assertEquals("new", debouncedSearch({}) { "new" })
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 240)
    }

    @Test fun cancellingRunningSearchSignalsWorkerAndSuppressesStaleResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        var delivered = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runCancellableSearch(onCancel = { cancelled.set(true); release.countDown() }) {
                entered.countDown()
                try { release.await(3, TimeUnit.SECONDS); "stale" } finally { exited.countDown() }
            }
            delivered = true
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(cancelled.get())
        assertTrue(exited.await(3, TimeUnit.SECONDS))
        assertFalse(delivered)
    }
}
