package com.gorite.cyclemap.tracking

import android.location.Location
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collects timestamped track points and serializes them as GPX 1.1.
 * Pure formatting logic lives in [GpxWriter] so it can be unit-tested on the JVM.
 */
class GpxRecorder(
    private val journal: GpxJournal? = null,
    recovery: GpxJournal.Recovery? = null,
    private val flushPolicy: FlushPolicy = FlushPolicy(maxPoints = 1, maxAgeMillis = 0L),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class FlushPolicy(val maxPoints: Int, val maxAgeMillis: Long) {
        init {
            require(maxPoints > 0)
            require(maxAgeMillis >= 0L)
        }
    }

    private val points = java.util.Collections.synchronizedList(mutableListOf<GpxPoint>())
    val startedAt: Long = recovery?.startedAt ?: System.currentTimeMillis()
    private val pending = mutableListOf<String>()
    private var pendingSinceMillis = 0L
    private var retryPending = false
    var paused: Boolean = recovery?.paused ?: false
        private set
    private var nextSegment = recovery != null

    init {
        if (recovery == null) enqueue("START\t$startedAt")
        else points.addAll(recovery.points)
        flush()
    }

    private fun enqueue(line: String) {
        if (pending.isEmpty()) pendingSinceMillis = clock()
        pending += line
    }

    @Synchronized
    fun flushIfDue() {
        if (journal == null || pending.isEmpty()) return
        // START / PAUSE / RESUME は即 flush するので、失敗時以外の pending は測位点のみ。
        if (retryPending || pending.size >= flushPolicy.maxPoints ||
            clock() - pendingSinceMillis >= flushPolicy.maxAgeMillis
        ) {
            flush()
        }
    }

    @Synchronized
    fun flush() {
        if (journal == null || pending.isEmpty()) return
        try {
            journal.append(pending)
            pending.clear()
            retryPending = false
        } catch (_: Exception) {
            // Keep both the live points and the pending writes; retry on the next fix/timer.
            retryPending = true
        }
    }

    @Synchronized
    fun pause() {
        paused = true
        nextSegment = true
        enqueue("PAUSE")
        flush()
    }

    @Synchronized
    fun resume() {
        paused = false
        nextSegment = true
        enqueue("RESUME")
        flush()
    }

    fun deleteJournal() { journal?.file?.delete() }


    val pointCount: Int get() = points.size

    fun addPoint(location: Location) {
        addPoint(
            GpxPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                elevationMeters = if (location.hasAltitude()) location.altitude else null,
                timestampMillis = location.time,
            ),
        )
    }

    @Synchronized
    fun addPoint(point: GpxPoint) {
        if (paused) return
        val recorded = point.copy(segmentStart = point.segmentStart || nextSegment)
        nextSegment = false
        points.add(recorded)
        enqueue(GpxJournal.pointLine(recorded))
        flushIfDue()
    }

    fun writeToFile(file: File): File {
        flush()
        val snapshot = synchronized(points) { points.toList() }
        require(snapshot.isNotEmpty()) { "Cannot save a GPX track without recorded points" }
        file.parentFile?.mkdirs()
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        try {
            java.io.FileOutputStream(tmpFile).use {
                it.write(GpxWriter.write(snapshot).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            tmpFile.delete()
            throw t
        }
        return file
    }
}

data class GpxPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timestampMillis: Long,
    val segmentStart: Boolean = false,
)

object GpxWriter {
    private val gpxTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun write(points: List<GpxPoint>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append('\n')
        append("<gpx version=\"1.1\" creator=\"CycleMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        append('\n')
        append("  <trk><name>CycleMap recording</name><trkseg>")
        append('\n')
        val formatter = checkNotNull(gpxTimeFormat.get())
        for ((index, p) in points.withIndex()) {
            if (index > 0 && p.segmentStart) append("  </trkseg><trkseg>\n")
            append("    <trkpt lat=\"").append(formatCoord(p.latitude))
                .append("\" lon=\"").append(formatCoord(p.longitude)).append("\">")
            if (p.elevationMeters != null) {
                append("<ele>").append("%.1f".format(Locale.US, p.elevationMeters)).append("</ele>")
            }
            append("<time>").append(formatter.format(Date(p.timestampMillis))).append("</time>")
            append("</trkpt>")
            append('\n')
        }
        append("  </trkseg></trk>")
        append('\n')
        append("</gpx>")
        append('\n')
    }

    private fun formatCoord(value: Double): String = "%.7f".format(Locale.US, value)
}
