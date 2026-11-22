package com.gorite.cyclemap.tracking

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GpxJournalTest {
    private fun point(time: Long) = GpxPoint(34.0, 131.0, 12.0, time)

    private val batchPolicy = GpxRecorder.FlushPolicy(maxPoints = 10, maxAgeMillis = 10_000L)

    private fun withJournal(test: (GpxJournal) -> Unit) {
        val dir = Files.createTempDirectory("gpx-batch").toFile()
        try {
            test(GpxJournal(File(dir, "ride.journal")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun restoresPointsAndRecordingStateAfterProcessLoss() {
        val dir = Files.createTempDirectory("gpx-journal").toFile()
        try {
            val journal = GpxJournal(File(dir, "ride.journal"))
            val original = GpxRecorder(journal)
            original.addPoint(point(1000))
            original.pause()
            original.addPoint(point(2000))
            val recovery = journal.restore()!!
            assertEquals(original.startedAt, recovery.startedAt)
            assertTrue(recovery.paused)
            assertEquals(listOf(point(1000)), recovery.points)
            val restored = GpxRecorder(journal, recovery)
            restored.resume()
            restored.addPoint(point(3000))
            val output = restored.writeToFile(File(dir, "ride.gpx"))
            assertTrue(output.readText().contains("</trkseg><trkseg>"))
            assertEquals(2, journal.restore()!!.points.size)
            assertFalse(journal.restore()!!.paused)
        } finally { dir.deleteRecursively() }
    }

    @Test fun skipsCorruptLinesAndRepairsTornTailBeforeAppending() {
        val dir = Files.createTempDirectory("gpx-corrupt").toFile()
        try {
            val file = File(dir, "ride.journal")
            val journal = GpxJournal(file)
            journal.append(listOf("START\t100", GpxJournal.pointLine(point(1000)), "P\tbad", "P\tNaN\t131\t-\t1200\t0"))
            file.appendText("P\t34.0\t")
            journal.append(listOf(GpxJournal.pointLine(point(2000))))
            assertEquals(listOf(point(1000), point(2000)), journal.restore()!!.points)
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedJournalWritesRetainPointsAndRetryWithoutStoppingRecorder() {
        val dir = Files.createTempDirectory("gpx-failure").toFile()
        try {
            val blockedParent = File(dir, "blocked").apply { writeText("not a directory") }
            val journal = GpxJournal(File(blockedParent, "ride.journal"))
            val recorder = GpxRecorder(journal)
            recorder.addPoint(point(1000))
            assertEquals(1, recorder.pointCount)
            blockedParent.delete()
            blockedParent.mkdir()
            recorder.flush()
            assertEquals(listOf(point(1000)), journal.restore()!!.points)
        } finally { dir.deleteRecursively() }
    }

    @Test fun defaultPolicyWritesEachPointImmediately() = withJournal { journal ->
        val recorder = GpxRecorder(journal)
        recorder.addPoint(point(1_000L))
        assertEquals(listOf(point(1_000L)), journal.restore()!!.points)
    }

    @Test fun batchWritesAllTenPointsTogetherDespiteTimerTicks() = withJournal { journal ->
        var now = 0L
        val recorder = GpxRecorder(journal, flushPolicy = batchPolicy, clock = { now })
        val startLine = "START\t${recorder.startedAt}"
        assertEquals(listOf(startLine), journal.file.readLines())
        val points = (1L..10L).map { point(it * 1_000L) }
        for (point in points.dropLast(1)) {
            now = point.timestampMillis
            recorder.addPoint(point)
            recorder.flushIfDue()
            assertEquals(listOf(startLine), journal.file.readLines())
        }

        now = 10_000L
        recorder.addPoint(points.last())
        val written = listOf(startLine) + points.map(GpxJournal::pointLine)
        assertEquals(written, journal.file.readLines())
        now += 10_000L
        recorder.flushIfDue()
        assertEquals(written, journal.file.readLines())
    }

    @Test fun batchAgeStartsAtFirstPendingPointAndCanFlushWithoutAnotherFix() = withJournal { journal ->
        var now = 0L
        val recorder = GpxRecorder(journal, flushPolicy = batchPolicy, clock = { now })
        val first = point(20_000L)
        val second = point(25_000L)
        now = 20_000L
        recorder.addPoint(first)
        now = 25_000L
        recorder.addPoint(second)
        now = 29_999L
        recorder.flushIfDue()
        assertTrue(journal.restore()!!.points.isEmpty())

        now = 30_000L
        recorder.flushIfDue()
        assertEquals(listOf(first, second), journal.restore()!!.points)

        now = 50_000L
        recorder.addPoint(point(50_000L))
        assertEquals(listOf(first, second), journal.restore()!!.points)
    }

    @Test fun pauseResumeAndFlushWritePendingPointsImmediately() = withJournal { journal ->
        val recorder = GpxRecorder(journal, flushPolicy = batchPolicy, clock = { 0L })
        val first = point(1_000L)
        val second = point(2_000L).copy(segmentStart = true)
        val third = point(3_000L).copy(segmentStart = true)
        recorder.addPoint(first)
        recorder.pause()
        val recovery = journal.restore()!!
        assertTrue(recovery.paused)
        assertEquals(listOf(first), recovery.points)
        val pausedLines = journal.file.readLines()
        assertEquals("PAUSE", pausedLines.last())

        val restored = GpxRecorder(journal, recovery, flushPolicy = batchPolicy, clock = { 0L })
        assertEquals(pausedLines, journal.file.readLines())
        restored.resume()
        assertFalse(journal.restore()!!.paused)
        assertEquals("RESUME", journal.file.readLines().last())
        restored.addPoint(point(2_000L))
        restored.resume() // A resume command can arrive while the recorder is already active.
        assertEquals(listOf(GpxJournal.pointLine(second), "RESUME"), journal.file.readLines().takeLast(2))
        restored.addPoint(point(3_000L))
        restored.flush()
        assertEquals(listOf(first, second, third), journal.restore()!!.points)
    }

    @Test fun failedBatchCanRetryOnTickWithoutMissingOrDuplicatingPoints() = withJournal { journal ->
        val recorder = GpxRecorder(journal, flushPolicy = batchPolicy, clock = { 0L })
        val savedJournal = File(journal.file.parentFile, "saved.journal")
        assertTrue(journal.file.renameTo(savedJournal))
        assertTrue(journal.file.mkdir())
        val points = (1L..10L).map { point(it * 1_000L) }
        points.forEach(recorder::addPoint)
        assertEquals(points.size, recorder.pointCount)
        assertTrue(journal.file.isDirectory)

        assertTrue(journal.file.delete())
        assertTrue(savedJournal.renameTo(journal.file))
        assertTrue(journal.restore()!!.points.isEmpty())
        recorder.flushIfDue()
        recorder.flushIfDue()
        assertEquals(points, journal.restore()!!.points)
    }

    @Test fun failedPauseRetriesOnNextTickEvenBelowBatchThresholds() = withJournal { journal ->
        val recorder = GpxRecorder(journal, flushPolicy = batchPolicy, clock = { 0L })
        val first = point(1_000L)
        recorder.addPoint(first)
        val savedJournal = File(journal.file.parentFile, "saved.journal")
        assertTrue(journal.file.renameTo(savedJournal))
        assertTrue(journal.file.mkdir())
        recorder.pause()

        assertTrue(journal.file.delete())
        assertTrue(savedJournal.renameTo(journal.file))
        recorder.flushIfDue()
        val recovery = journal.restore()!!
        assertTrue(recovery.paused)
        assertEquals(listOf(first), recovery.points)
    }
}
