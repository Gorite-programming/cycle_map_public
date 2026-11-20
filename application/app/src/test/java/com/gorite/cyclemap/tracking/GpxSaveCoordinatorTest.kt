package com.gorite.cyclemap.tracking

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GpxSaveCoordinatorTest {
    @Test
    fun stopWaitsForFileAndMainThreadCompletionAndIncludesLastPoint() {
        val io = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        var stops = 0
        var completions = 0
        val coordinator = GpxSaveCoordinator(io::addLast, main::addLast) { stops++ }
        val directory = Files.createTempDirectory("gpx-stop").toFile()
        try {
            val output = File(directory, "track.gpx")
            val recorder = GpxRecorder()
            recorder.addPoint(GpxPoint(34.0, 131.0, null, 1000))
            recorder.addPoint(GpxPoint(34.1, 131.1, null, 2000))
            recorder.pause()
            coordinator.save({ recorder.writeToFile(output); Unit }) {
                assertTrue(it.isSuccess)
                assertTrue(output.readText().contains("lat=\"34.1000000\""))
                assertTrue(output.readText().endsWith("</gpx>\n"))
                completions++
            }
            coordinator.requestStop()
            coordinator.requestStop()
            coordinator.save({ fail("Duplicate save") }) { fail("Duplicate completion") }
            assertEquals(0, stops)
            assertFalse(output.exists())
            io.removeFirst().invoke()
            assertEquals(0, stops)
            assertTrue(coordinator.saving)
            main.removeFirst().invoke()
            coordinator.requestStop()
            assertFalse(coordinator.saving)
            assertEquals(1, completions)
            assertEquals(1, stops)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedWriteCompletesBeforeStopping() {
        val io = ArrayDeque<() -> Unit>()
        var failed = false
        var stopped = false
        val coordinator = GpxSaveCoordinator(io::addLast, { it() }) {
            assertTrue(failed)
            stopped = true
        }
        coordinator.save({ error("Disk full") }) { failed = it.isFailure }
        coordinator.requestStop()
        assertFalse(stopped)
        io.removeFirst().invoke()
        assertTrue(stopped)
    }

    @Test
    fun restartCancelsPendingStopAndEmptyStopIsIdempotent() {
        val io = ArrayDeque<() -> Unit>()
        var stops = 0
        val coordinator = GpxSaveCoordinator(io::addLast, { it() }) { stops++ }
        coordinator.save({}) {}
        coordinator.requestStop()
        coordinator.resumeTracking()
        io.removeFirst().invoke()
        assertEquals(0, stops)
        coordinator.requestStop()
        coordinator.requestStop()
        assertEquals(1, stops)
    }
    @Test
    fun saveReturnsToIdleWhenDispatchThrows() {
        var stops = 0
        var failure: Throwable? = null
        val coordinator = GpxSaveCoordinator({ throw java.util.concurrent.RejectedExecutionException("shutdown") }, { it() }) { stops++ }
        coordinator.save({ fail("not written") }) { failure = it.exceptionOrNull() }
        assertTrue(failure is java.util.concurrent.RejectedExecutionException)
        assertFalse(coordinator.saving)
        coordinator.requestStop()
        assertEquals(1, stops)
    }
}
