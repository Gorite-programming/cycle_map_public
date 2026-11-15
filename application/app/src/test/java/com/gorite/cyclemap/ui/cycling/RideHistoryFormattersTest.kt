package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.tracking.GpxTrackPoint
import com.gorite.cyclemap.tracking.RideHistorySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

class RideHistoryFormattersTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    private fun rideAt(
        date: String,
        hour: Int = 9,
        distanceMeters: Double = 10_000.0,
        durationSeconds: Long = 1800L,
        elevationGainMeters: Double = 0.0,
        points: List<GpxTrackPoint> = emptyList(),
    ): RideHistorySummary {
        val startMs = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val avgSpeed = if (durationSeconds > 0) (distanceMeters / durationSeconds) * 3.6 else 0.0
        return RideHistorySummary(
            file = File("ride_$date.gpx"),
            fileName = "ride_$date.gpx",
            startTime = Date(startMs),
            totalDistanceMeters = distanceMeters,
            totalDurationSeconds = durationSeconds,
            averageSpeedKmh = avgSpeed,
            maxSpeedKmh = avgSpeed * 1.3,
            elevationGainMeters = elevationGainMeters,
            points = points,
        )
    }

    private fun point(ele: Double?): GpxTrackPoint =
        GpxTrackPoint(
            latitude = 34.4,
            longitude = 132.4,
            elevationMeters = ele,
            timestampMillis = 1_700_000_000_000L,
        )

    @Test
    fun historySectionTitleDistinguishesLoadingAndLoadedCounts() {
        assertEquals("過去のライド履歴", historySectionTitle(null))
        assertEquals("過去のライド履歴 (0件)", historySectionTitle(0))
        assertEquals("過去のライド履歴 (3件)", historySectionTitle(3))
    }

    @Test
    fun emptyGuideChangesStepsWhenRecording() {
        val idle = buildEmptyRideHistoryGuide(isRecording = false)
        assertEquals("最初のライドを記録しましょう", idle.title)
        assertEquals("記録開始 → 走る → 記録を止めると保存されます", idle.steps)
        assertEquals("保存すると距離・時間・平均速度・獲得標高が見られます", idle.caption)

        val recording = buildEmptyRideHistoryGuide(isRecording = true)
        assertEquals("最初のライドを記録しましょう", recording.title)
        assertEquals("止めると履歴に追加されます", recording.steps)
        assertEquals("保存すると距離・時間・平均速度・獲得標高が見られます", recording.caption)
    }

    @Test
    fun summarizeRideHistoryCountsCurrentMonthAndTotal() {
        val rides = listOf(
            rideAt("2026-11-15", distanceMeters = 30_400.0),
            rideAt("2026-11-01", distanceMeters = 52_000.0),
            rideAt("2026-10-31", distanceMeters = 40_000.0),
            rideAt("2026-10-10", distanceMeters = -100.0),
        )
        val totals = summarizeRideHistory(rides, today = LocalDate.parse("2026-11-15"), zone = zone)
        assertEquals(2, totals.monthCount)
        assertEquals(82_400.0, totals.monthDistanceMeters, 1e-6)
        assertEquals(4, totals.totalCount)
        assertEquals(122_400.0, totals.totalDistanceMeters, 1e-6)
    }

    @Test
    fun formatRideHistoryTotalsSummaryShowsMonthOnlyWhenAllInCurrentMonth() {
        val rides = listOf(
            rideAt("2026-11-02", distanceMeters = 20_000.0),
            rideAt("2026-11-08", distanceMeters = 32_400.0),
            rideAt("2026-11-14", distanceMeters = 30_000.0),
        )
        val totals = summarizeRideHistory(rides, today = LocalDate.parse("2026-11-15"), zone = zone)
        assertEquals("今月 3 回 ・ 82.4 km", formatRideHistoryTotalsSummary(totals))
    }

    @Test
    fun formatRideHistoryTotalsSummaryIncludesTotalWhenOlderRidesExist() {
        val rides = listOf(
            rideAt("2026-11-10", distanceMeters = 25_000.0),
            rideAt("2026-10-20", distanceMeters = 77_300.0),
        )
        val totals = summarizeRideHistory(rides, today = LocalDate.parse("2026-11-15"), zone = zone)
        assertEquals(
            "今月 1 回 ・ 25.0 km（通算 2 回 ・ 102.3 km）",
            formatRideHistoryTotalsSummary(totals),
        )
    }

    @Test
    fun elevationGainShowsDashWhenNoElevationData() {
        val noEle = rideAt(
            "2026-11-15",
            elevationGainMeters = 0.0,
            points = listOf(point(null), point(null), point(null)),
        )
        assertFalse(hasElevationData(noEle))
        assertEquals("—", formatElevationGain(noEle))

        val singleEle = rideAt(
            "2026-11-15",
            elevationGainMeters = 0.0,
            points = listOf(point(12.0), point(null)),
        )
        assertFalse(hasElevationData(singleEle))
        assertEquals("—", formatElevationGain(singleEle))
    }

    @Test
    fun elevationGainFormatsZeroWhenFlatElevationExistsAndPositiveGain() {
        val flatWithEle = rideAt(
            "2026-11-15",
            elevationGainMeters = 0.0,
            points = listOf(point(10.0), point(10.0)),
        )
        assertTrue(hasElevationData(flatWithEle))
        assertEquals("+0 m", formatElevationGain(flatWithEle))

        val hilly = rideAt(
            "2026-11-15",
            elevationGainMeters = 245.6,
            points = listOf(point(10.0), point(255.6)),
        )
        assertTrue(hasElevationData(hilly))
        assertEquals("+246 m", formatElevationGain(hilly))
    }

    @Test
    fun distanceSpeedAndDurationFormattingHandlesEdgeValues() {
        assertEquals("12.35 km", formatRideDistance(12_345.0))
        assertEquals("0.00 km", formatRideDistance(Double.NaN))
        assertEquals("18.5 km/h", formatRideSpeed(18.54))
        assertEquals("0.0 km/h", formatRideSpeed(-3.0))
        assertEquals("00:45", formatDuration(45L))
        assertEquals("1:01:05", formatDuration(3665L))
        assertEquals("00:00", formatDuration(-10L))
    }
}
