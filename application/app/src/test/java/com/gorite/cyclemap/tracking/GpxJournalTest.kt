package com.gorite.cyclemap.tracking

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GpxJournalTest {
    private fun point(time: Long) = GpxPoint(34.0, 131.0, 12.0, time)

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
}
