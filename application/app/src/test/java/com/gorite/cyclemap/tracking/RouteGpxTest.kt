package com.gorite.cyclemap.tracking

import com.gorite.cyclemap.routing.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class RouteGpxTest {
    @Test
    fun exportUsesFixedCoordinatePrecisionAndEscapesXml() {
        val xml = buildRouteGpx(listOf(RoutePoint(35.123456789, 139.987654321)), "A&B <route>", 0L)!!
        assert(xml.contains("lat=\"35.1234568\" lon=\"139.9876543\""))
        assert(xml.contains("A&amp;B &lt;route&gt;"))
        assert(xml.contains("creator=\"CycleMap\""))
    }

    @Test
    fun exportReturnsNullForEmptyRoute() {
        assertNull(buildRouteGpx(emptyList(), "empty", 0L))
    }

    @Test
    fun exportedTrackCanBeParsedBack() {
        val expected = listOf(RoutePoint(34.1, 131.2), RoutePoint(34.2, 131.3))
        val xml = buildRouteGpx(expected, "route", 1234L)!!
        val parsed = GpxParser.parseRoutePoints(ByteArrayInputStream(xml.toByteArray()))
        assertEquals(expected, parsed)
    }

    @Test
    fun parsesTrackPointsWithAndWithoutNamespaceAndPrefersTracks() {
        val namespaced = """<g:gpx xmlns:g="http://www.topografix.com/GPX/1/1"><g:trk><g:trkseg><g:trkpt lat="34" lon="131"/><g:trkpt lat="35" lon="132"/></g:trkseg></g:trk><g:rte><g:rtept lat="36" lon="133"/><g:rtept lat="37" lon="134"/></g:rte></g:gpx>"""
        assertEquals(listOf(RoutePoint(34.0, 131.0), RoutePoint(35.0, 132.0)), parse(namespaced))
        assertEquals(listOf(RoutePoint(34.0, 131.0), RoutePoint(35.0, 132.0)), parse("<gpx><trk><trkseg><trkpt lat=\"34\" lon=\"131\"/><trkpt lat=\"35\" lon=\"132\"/></trkseg></trk></gpx>"))
    }

    @Test
    fun parsesRoutePointsWhenNoTrackIsPresent() {
        assertEquals(listOf(RoutePoint(34.0, 131.0), RoutePoint(35.0, 132.0)), parse("<gpx><rte><rtept lat=\"34\" lon=\"131\"/><rtept lat=\"35\" lon=\"132\"/></rte></gpx>"))
    }

    @Test
    fun rejectsMalformedXmlAndSinglePoint() {
        assertThrows(Exception::class.java) { parse("<gpx><trkpt") }
        assertThrows(IllegalArgumentException::class.java) { parse("<gpx><trk><trkpt lat=\"34\" lon=\"131\"/></trk></gpx>") }
    }

    @Test
    fun downsamplesRoutesAboveFiftyThousandPointsAndKeepsEndpoints() {
        val points = (0..50_000).map { RoutePoint(it.toDouble(), it.toDouble()) }
        val sampled = downsampleRoutePoints(points)
        assertEquals(50_000, sampled.size)
        assertEquals(points.first(), sampled.first())
        assertEquals(points.last(), sampled.last())
    }

    private fun parse(xml: String) = GpxParser.parseRoutePoints(ByteArrayInputStream(xml.toByteArray()))
}
