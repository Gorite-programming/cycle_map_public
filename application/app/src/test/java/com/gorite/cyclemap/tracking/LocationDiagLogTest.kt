package com.gorite.cyclemap.tracking

import java.io.IOException
import java.io.StringWriter
import java.io.Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDiagLogTest {
    private fun fix(elapsed: Long = 1_000L, accuracy: Float? = 8.5f, speed: Float? = 4.2f) = DiagFix(
        elapsedMillis = elapsed,
        epochMillis = 1_700_000_000_000L + elapsed,
        latitude = 35.1234567,
        longitude = 135.7654321,
        accuracyMeters = accuracy,
        speedMps = speed,
        speedAccuracyMps = 0.4f,
        bearingDegrees = null,
        provider = "fused",
        satellites = 11,
    )

    @Test
    fun writesHeaderAndOneLinePerFix() {
        val out = StringWriter()
        val log = LocationDiagLog(out, flushEveryLines = 1)
        log.append(fix(), GateVerdict.ACCEPT)
        log.append(fix(elapsed = 2_000L, accuracy = null, speed = null), GateVerdict.STATIONARY)
        log.close()
        val lines = out.toString().lines().filter { it.isNotEmpty() }
        assertEquals(LocationDiagLog.HEADER, lines[0])
        assertEquals("1000,1700000001000,35.1234567,135.7654321,8.50,4.20,0.40,,fused,11,ACCEPT", lines[1])
        // 無い値は空欄
        assertEquals("2000,1700000002000,35.1234567,135.7654321,,,0.40,,fused,11,STATIONARY", lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun rejectedFixesAreLoggedToo() {
        val out = StringWriter()
        val log = LocationDiagLog(out)
        log.append(fix(), GateVerdict.REJECT_JUMP)
        log.append(fix(), GateVerdict.REJECT_INACCURATE)
        log.close()
        val text = out.toString()
        assertTrue(text.contains("REJECT_JUMP"))
        assertTrue(text.contains("REJECT_INACCURATE"))
    }

    @Test
    fun aFailingWriterNeverThrowsIntoTheLocationPipeline() {
        val broken = object : Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) = throw IOException("disk full")
            override fun flush() = throw IOException("disk full")
            override fun close() = throw IOException("disk full")
        }
        val log = LocationDiagLog(broken, flushEveryLines = 1)
        log.append(fix(), GateVerdict.ACCEPT)
        log.append(fix(), GateVerdict.ACCEPT)
        log.close()
    }
}
