package com.gorite.cyclemap.tracking

import com.gorite.cyclemap.routing.RoutePoint
import java.io.InputStream
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/** Build a GPX 1.1 track from route geometry. Returns null when there is no route to export. */
fun buildRouteGpx(points: List<RoutePoint>, name: String, nowMillis: Long): String? {
    if (points.isEmpty()) return null
    val timestamp = Instant.ofEpochMilli(nowMillis).toString()
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"CycleMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <metadata><name>").append(xmlEscape(name)).append("</name><time>")
            .append(timestamp).append("</time></metadata>\n")
        append("  <trk><name>").append(xmlEscape(name)).append("</name><trkseg>\n")
        points.forEach { point ->
            append("    <trkpt lat=\"").append(String.format(Locale.US, "%.7f", point.latitude))
                .append("\" lon=\"").append(String.format(Locale.US, "%.7f", point.longitude))
                .append("\"/>\n")
        }
        append("  </trkseg></trk>\n</gpx>")
    }
}

private fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { char ->
        append(
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '\"' -> "&quot;"
                '\'' -> "&apos;"
                else -> char.toString()
            },
        )
    }
}

/** Parse a planned route from GPX, preferring track points and falling back to route points. */
fun parseRoutePoints(inputStream: InputStream): List<RoutePoint> {
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    val trackPoints = ArrayList<RoutePoint>()
    val routePoints = ArrayList<RoutePoint>()
    var pointElements = 0
    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val tagName = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
            if (tagName == "trkpt" || tagName == "rtept") {
                pointElements++
                require(pointElements <= MAX_ROUTE_POINTS) {
                    "GPXの点数が多すぎます (上限 ${MAX_ROUTE_POINTS} 点)"
                }
            val lat = attributes.getValue("lat")?.toDoubleOrNull()
            val lon = attributes.getValue("lon")?.toDoubleOrNull()
            if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                val points = if (tagName == "trkpt") trackPoints else routePoints
                points.add(RoutePoint(lat, lon))
                require(trackPoints.size + routePoints.size <= MAX_ROUTE_POINTS) {
                    "GPXの点数が多すぎます (上限 ${MAX_ROUTE_POINTS} 点)"
                }
            }
            }
        }
    }
    val reader = factory.newSAXParser().xmlReader
    runCatching { reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { reader.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    reader.contentHandler = handler
    reader.parse(org.xml.sax.InputSource(inputStream))
    val points = if (trackPoints.isNotEmpty()) trackPoints else routePoints
    require(points.size >= 2) { "GPXに有効な経路点が2点以上ありません" }
    return downsampleRoutePoints(points)
}

/** Reduce dense routes to at most 50,000 points, retaining the first and last point. */
fun downsampleRoutePoints(points: List<RoutePoint>, maxPoints: Int = MAX_DISPLAY_ROUTE_POINTS): List<RoutePoint> {
    require(maxPoints >= 2)
    if (points.size <= maxPoints) return points
    val lastIndex = points.lastIndex
    return List(maxPoints) { index ->
        points[(index.toLong() * lastIndex / (maxPoints - 1)).toInt()]
    }
}

private const val MAX_ROUTE_POINTS = 500_000
private const val MAX_DISPLAY_ROUTE_POINTS = 50_000
