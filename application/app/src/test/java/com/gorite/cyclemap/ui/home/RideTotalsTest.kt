package com.gorite.cyclemap.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RideTotalsTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun at(date: String, hour: Int) =
        LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun countsTodayAndThisWeekFromMonday() {
        // 2026-11-12 は木曜。今週は 11-09 (月) から
        val rides = listOf(
            at("2026-11-12", 8) to 5_000.0, // 今日
            at("2026-11-12", 18) to 2_500.0, // 今日
            at("2026-11-09", 7) to 10_000.0, // 今週の月曜
            at("2026-11-08", 9) to 30_000.0, // 先週の日曜 (数えない)
            at("2026-11-13", 9) to 1_000.0, // 明日 (時計のずれ。数えない)
        )
        val totals = sumRideTotals(rides, LocalDate.parse("2026-11-12"), zone)
        assertEquals(7_500.0, totals.todayMeters, 1e-6)
        assertEquals(17_500.0, totals.weekMeters, 1e-6)
    }

    @Test
    fun emptyHistoryIsZero() {
        val totals = sumRideTotals(emptyList(), LocalDate.parse("2026-11-12"), zone)
        assertEquals(0.0, totals.todayMeters, 0.0)
        assertEquals(0.0, totals.weekMeters, 0.0)
        assertEquals("0.0", formatKm(totals.todayMeters))
    }
}
