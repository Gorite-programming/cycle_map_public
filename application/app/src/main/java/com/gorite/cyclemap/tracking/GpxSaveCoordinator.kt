package com.gorite.cyclemap.tracking

/** State is confined to the caller (service main thread); only [write] runs on IO. */
internal class GpxSaveCoordinator(
    private val dispatchIo: (() -> Unit) -> Unit,
    private val dispatchCompletion: (() -> Unit) -> Unit,
    private val onStopped: () -> Unit,
) {
    var saving = false
        private set
    private var stopRequested = false
    private var stopped = false

    fun resumeTracking() {
        stopRequested = false
        stopped = false
    }

    fun save(write: () -> Unit, onComplete: (Result<Unit>) -> Unit) {
        if (saving) return
        saving = true
        dispatchIo {
            val result = runCatching(write)
            dispatchCompletion {
                try {
                    onComplete(result)
                } finally {
                    saving = false
                    stopIfReady()
                }
            }
        }
    }

    fun requestStop() {
        stopRequested = true
        stopIfReady()
    }

    private fun stopIfReady() {
        if (stopRequested && !saving && !stopped) {
            stopped = true
            onStopped()
        }
    }
}
