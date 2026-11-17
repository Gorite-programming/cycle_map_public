package com.gorite.cyclemap.tracking

import java.io.File
import java.io.RandomAccessFile

/** Append-only, internal-storage recovery log. Each committed line is independently readable. */
class GpxJournal(val file: File) {
    data class Recovery(val startedAt: Long, val paused: Boolean, val points: List<GpxPoint>)

    @Synchronized
    fun append(lines: List<String>) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { output ->
            val end = output.length()
            try {
                output.seek(end)
                if (end > 0) {
                    output.seek(end - 1)
                    val lastByte = output.read()
                    output.seek(end)
                    if (lastByte != 10) output.write(10)
                }
                output.write((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            } catch (e: Exception) {
                runCatching { output.setLength(end) }
                throw e
            }
        }
    }

    fun restore(): Recovery? {
        if (!file.isFile) return null
        var startedAt: Long? = null
        var paused = false
        val points = mutableListOf<GpxPoint>()
        file.forEachLine { line ->
            runCatching {
                val fields = line.split('\t')
                when (fields[0]) {
                    "START" -> startedAt = fields[1].toLong()
                    "PAUSE" -> paused = true
                    "RESUME" -> paused = false
                    "P" -> {
                        val p = GpxPoint(fields[1].toDouble(), fields[2].toDouble(),
                            fields[3].takeUnless { it == "-" }?.toDouble(), fields[4].toLong(), fields[5] == "1")
                        require(p.latitude.isFinite() && p.latitude in -90.0..90.0 &&
                            p.longitude.isFinite() && p.longitude in -180.0..180.0 &&
                            (p.elevationMeters == null || p.elevationMeters.isFinite()))
                        points += p
                    }
                }
            } // A torn or corrupt line must never hide the rest of a ride.
        }
        return (startedAt ?: points.firstOrNull()?.timestampMillis)?.let { Recovery(it, paused, points) }
    }

    companion object {
        fun pointLine(p: GpxPoint) = listOf("P", p.latitude, p.longitude,
            p.elevationMeters ?: "-", p.timestampMillis, if (p.segmentStart) 1 else 0).joinToString("\t")
    }
}
