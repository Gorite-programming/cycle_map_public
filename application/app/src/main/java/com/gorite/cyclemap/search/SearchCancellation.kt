package com.gorite.cyclemap.search

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val searchExecutor = Executors.newCachedThreadPool { task ->
    Thread(task, "CycleMap-search").apply { isDaemon = true }
}

/** Cancels the database query immediately, even while its worker is blocked in SQLite. */
internal suspend fun <T> runCancellableSearch(onCancel: () -> Unit, search: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { onCancel() }
        searchExecutor.execute {
            if (continuation.isActive) {
                try {
                    val result = search()
                    continuation.resume(result)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

internal suspend fun <T> debouncedSearch(onCancel: () -> Unit, search: () -> T): T {
    kotlinx.coroutines.delay(250)
    return runCancellableSearch(onCancel, search)
}
