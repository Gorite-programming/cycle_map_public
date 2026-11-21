package com.gorite.cyclemap.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationScreenTest {

    @Test
    fun formatsDistanceCorrectly() {
        assertEquals("450 m", formatDistance(450.0))
        assertEquals("1.2 km", formatDistance(1200.0))
        assertEquals("15.0 km", formatDistance(15000.0))
    }

    @Test
    fun formatsDurationCorrectly() {
        assertEquals("5分", formatDuration(300.0))
        assertEquals("1時間15分", formatDuration(4500.0))
        assertEquals("2時間0分", formatDuration(7200.0))
        assertEquals("1日2時間", formatDuration(26 * 3600.0))
    }

    @Test
    fun formatsEtaCorrectly() {
        val etaStr = formatEta(3600.0) // 1 hour ahead
        assertTrue(etaStr.contains("到着"))
        assertTrue(etaStr.matches(Regex("\\d{2}:\\d{2} 到着")))
    }

    @Test
    fun formatArrivalClockRoundsToNearestMinuteAndHandlesDayRollover() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        fun epoch(hour: Int, minute: Int, second: Int): Long =
            java.time.ZonedDateTime.of(2026, 11, 20, hour, minute, second, 0, zone)
                .toInstant()
                .toEpochMilli()

        assertEquals("01:45", formatArrivalClock(epoch(1, 45, 29), zone))
        assertEquals("01:46", formatArrivalClock(epoch(1, 45, 30), zone))
        assertEquals("00:00", formatArrivalClock(epoch(23, 59, 40), zone))
    }

    @Test
    fun formatEtaUsesSameRoundingAsFormatArrivalClock() {
        val zone = java.time.ZoneId.of("Asia/Tokyo")
        val base = java.time.ZonedDateTime.of(2026, 11, 20, 1, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()

        // 45分29秒後 -> 01:45、45分30秒後 -> 01:46
        assertEquals("01:45 到着", formatEta(45 * 60.0 + 29.0, base, zone))
        assertEquals("01:46 到着", formatEta(45 * 60.0 + 30.0, base, zone))

        // 上段 (formatEta) と下段 (formatArrivalClock) が同じ時刻から同じ HH:mm を得る
        val targetMillis = base + (45 * 60L + 30L) * 1000L
        assertEquals("${formatArrivalClock(targetMillis, zone)} 到着", formatEta(45 * 60.0 + 30.0, base, zone))
    }

    @Test
    fun etaInfoFromNavStatsUsesTheStatsArrivalTime() {
        val stats = com.gorite.cyclemap.routing.NavigationStats(
            remainingMeters = 30_000.0,
            elapsedSeconds = 600.0,
            effectiveSpeedMps = 4.0,
            durationRemainingSeconds = 7_500.0,
            etaEpochMillis = 1_700_000_000_000L,
            isArrived = false,
        )
        val eta = etaInfoFromNavStats(stats, 14.4)!!
        // 上段・下段とも統計の到着時刻をそのまま使うので、同じ時刻になる (別の計算で 1 分ずれない)
        assertEquals(1_700_000_000_000L, eta.arrivalEpochMillis)
        assertEquals(30_000.0, eta.remainingDistanceMeters, 0.0)
        assertEquals(7_500.0, eta.remainingSeconds, 0.0)
        assertEquals(14.4, eta.currentSpeedKmh, 0.0)
    }

    @Test
    fun etaInfoFromNavStatsReturnsNullWhenArrivalCannotBeComputed() {
        val noSpeed = com.gorite.cyclemap.routing.NavigationStats(
            remainingMeters = 1_000.0,
            elapsedSeconds = 0.0,
            effectiveSpeedMps = 0.0,
            durationRemainingSeconds = Double.POSITIVE_INFINITY,
            etaEpochMillis = null,
            isArrived = false,
        )
        assertEquals(null, etaInfoFromNavStats(noSpeed, 0.0))
        // 時刻だけあっても残り時間が無限なら使わない (時刻へ桁あふれさせない)
        assertEquals(null, etaInfoFromNavStats(noSpeed.copy(etaEpochMillis = 1L), 0.0))
    }
}
