package com.gorite.cyclemap.tracking

import com.gorite.cyclemap.utils.haversineMeters
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GpxTrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double?,
    val timestampMillis: Long,
    val speedKmh: Double = 0.0,
    val distanceMetersFromStart: Double = 0.0,
)

data class RideHistorySummary(
    val file: File,
    val fileName: String,
    val startTime: Date,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Long,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val elevationGainMeters: Double,
    val points: List<GpxTrackPoint>,
)

object GpxParser {
    /** Parse planned track/route geometry without changing the ride-history parser behavior. */
    fun parseRoutePoints(inputStream: InputStream): List<com.gorite.cyclemap.routing.RoutePoint> =
        com.gorite.cyclemap.tracking.parseRoutePoints(inputStream)

    internal fun parseTimestamp(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // 書式を順に試す。合わない書式の例外は想定どおりなので捨てる。
        try {
            return Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {}

        try {
            return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: Exception) {}

        try {
            return ZonedDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: Exception) {}

        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_DATE_TIME)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } catch (_: Exception) {}

        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy/MM/dd HH:mm:ss",
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = sdf.parse(trimmed)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }

        return null
    }

    fun parse(file: File): RideHistorySummary? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.inputStream().use { parse(it, file) }
        } catch (e: Exception) {
            // 壊れた記録は履歴に出さないが、理由をログに残す (黙って消えると原因がわからないため)
            android.util.Log.w("CycleMapGpx", "GPX_PARSE_FAILED file=${file.name}: ${e.message}", e)
            null
        }
    }

    fun parse(inputStream: InputStream, sourceFile: File): RideHistorySummary {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        return parse(parser, sourceFile)
    }

    fun parse(parser: XmlPullParser, sourceFile: File): RideHistorySummary {
        val rawPoints = ArrayList<GpxTrackPoint>()
        // 各点が属する trkseg の番号。一時停止で区切られた区間の間 (移動していない間) は距離・時間に数えない
        val segmentOfPoint = ArrayList<Int>()
        var segmentIndex = -1
        var eventType = parser.eventType
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle: Double? = null
        var currentTime: Long? = null
        var insideTrkpt = false
        var currentTag = ""
        var lastValidTime = 0L
        val textBuffer = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    textBuffer.setLength(0)
                    if (currentTag == "trkseg") segmentIndex++
                    if (currentTag == "trkpt") {
                        insideTrkpt = true
                        currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        currentEle = null
                        currentTime = null
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideTrkpt) {
                        textBuffer.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (insideTrkpt) {
                        val text = textBuffer.toString().trim()
                        when (parser.name) {
                            "ele" -> {
                                if (text.isNotEmpty()) {
                                    currentEle = text.toDoubleOrNull()
                                }
                            }
                            "time" -> {
                                if (text.isNotEmpty()) {
                                    currentTime = parseTimestamp(text)
                                }
                            }
                            "trkpt" -> {
                                val lat = currentLat
                                val lon = currentLon
                                if (lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
                                    lat in -90.0..90.0 && lon in -180.0..180.0
                                ) {
                                    val timeMs = currentTime ?: lastValidTime.takeIf { it > 0L }
                                        ?: sourceFile.lastModified().takeIf { it > 0L } ?: 0L
                                    if (timeMs > 0L) {
                                        lastValidTime = timeMs
                                    }
                                    rawPoints.add(
                                        GpxTrackPoint(
                                            latitude = lat,
                                            longitude = lon,
                                            elevationMeters = currentEle,
                                            timestampMillis = timeMs,
                                        ),
                                    )
                                    segmentOfPoint.add(segmentIndex.coerceAtLeast(0))
                                }
                                insideTrkpt = false
                            }
                        }
                    }
                    currentTag = ""
                    textBuffer.setLength(0)
                }
            }
            eventType = parser.next()
        }

        // 統計計算 (距離、速度、獲得標高)
        var totalDist = 0.0
        val elevation = ElevationGainAccumulator()
        var maxSpd = 0.0
        val computedPoints = ArrayList<GpxTrackPoint>(rawPoints.size)

        for (i in rawPoints.indices) {
            val curr = rawPoints[i]
            val sameSegment = i > 0 && segmentOfPoint[i] == segmentOfPoint[i - 1]
            val height = curr.elevationMeters
            if (!sameSegment || height == null || !height.isFinite()) elevation.resetBaseline()
            if (height != null) elevation.add(height)
            var spd = 0.0
            if (sameSegment) {
                val prev = rawPoints[i - 1]
                val d = haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
                totalDist += d

                if (prev.timestampMillis > 0L && curr.timestampMillis > 0L) {
                    val dtSeconds = (curr.timestampMillis - prev.timestampMillis) / 1000.0
                    if (dtSeconds > 0.5) {
                        spd = (d / dtSeconds) * 3.6
                        if (spd in 1.0..90.0 && spd > maxSpd) {
                            maxSpd = spd
                        }
                    }
                }
            }
            computedPoints.add(
                curr.copy(
                    speedKmh = spd,
                    distanceMetersFromStart = totalDist,
                ),
            )
        }

        val validTimePoints = computedPoints.filter { it.timestampMillis > 0L }
        val start = validTimePoints.firstOrNull()?.timestampMillis ?: sourceFile.lastModified().takeIf { it > 0L } ?: 0L
        val end = validTimePoints.lastOrNull()?.timestampMillis ?: start
        // 区間ごとの時間を足す (一時停止で区切った間の時間は含めない)。区切りが無ければ従来どおり最初から最後まで
        val durSec = if (segmentOfPoint.distinct().size <= 1) ((end - start) / 1000L).coerceAtLeast(0L) else
            computedPoints.indices.groupBy { segmentOfPoint[it] }.values.sumOf { idx ->
                val ts = idx.map { computedPoints[it].timestampMillis }.filter { it > 0L }
                if (ts.size < 2) 0L else ((ts.last() - ts.first()) / 1000L).coerceAtLeast(0L)
            }
        val avgSpd = if (durSec > 0) (totalDist / durSec) * 3.6 else 0.0

        return RideHistorySummary(
            file = sourceFile,
            fileName = sourceFile.name,
            startTime = Date(start),
            totalDistanceMeters = totalDist,
            totalDurationSeconds = durSec,
            averageSpeedKmh = avgSpd,
            maxSpeedKmh = maxSpd,
            elevationGainMeters = elevation.gainMeters,
            points = computedPoints,
        )
    }
}
