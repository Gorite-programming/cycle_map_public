package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.contract.RouteOption
import com.gorite.cyclemap.contract.TravelModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteChoicesTest {
    private fun option(
        id: String,
        km: Double,
        gain: Double,
        pts: Int,
        primary: Boolean = false,
        title: String = id,
    ) = RouteOption(
        id = id, title = title, travelMode = TravelModeType.Bicycle, distanceMeters = km * 1000.0,
        durationSeconds = 0.0, elevationGainMeters = gain,
        points = List(pts) { 34.0 + it * 0.001 to 132.0 }, isPrimary = primary,
    )

    @Test
    fun choicesShowDistanceAndGain() {
        val c = routeChoicesOf(listOf(option("a", 9.68, 175.4, 3, true), option("b", 10.04, 0.0, 3)))
        assertEquals("ルート1", c[0].label)
        assertEquals("9.7km ・ +175m", c[0].detail)
        assertEquals("10.0km", c[1].detail) // 獲得標高が 0 のときは出さない
        assertEquals(9680.0, c[0].distanceMeters, 1e-9)
        assertEquals(175.4, c[0].elevationGainMeters, 1e-9)
        assertEquals(10040.0, c[1].distanceMeters, 1e-9)
        assertEquals(0.0, c[1].elevationGainMeters, 1e-9)
    }

    @Test
    fun candidateDurationUsesEachDistanceAndAverageSpeed() {
        val choices = routeChoicesOf(listOf(option("a", 30.0, 0.0, 3), option("b", 45.0, 0.0, 3)))
        assertEquals(5400.0, choices[0].estimatedDurationSeconds(20.0), 1e-9)
        assertEquals(8100.0, choices[1].estimatedDurationSeconds(20.0), 1e-9)
        assertEquals("約1時間30分", formatRemainingTime(choices[0].estimatedDurationSeconds(20.0)))
        assertEquals("約2時間15分", formatRemainingTime(choices[1].estimatedDurationSeconds(20.0)))
    }

    @Test
    fun candidateDurationDefaultsToFifteenKmhWithoutAverageSpeed() {
        val choice = routeChoicesOf(listOf(option("a", 10.0, 0.0, 3))).single()
        assertEquals(2400.0, choice.estimatedDurationSeconds(null), 1e-9)
        assertEquals("約40分", formatRemainingTime(choice.estimatedDurationSeconds(null)))
    }

    @Test
    fun candidateDurationDefaultsToFifteenKmhForInvalidAverageSpeed() {
        val choice = routeChoicesOf(listOf(option("a", 15.0, 0.0, 3))).single()
        listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { speed ->
            assertEquals(3600.0, choice.estimatedDurationSeconds(speed), 1e-9)
        }
    }

    @Test
    fun candidateDurationUsesExistingMinuteRounding() {
        val choices = routeChoicesOf(listOf(option("a", 0.1, 0.0, 3), option("b", 0.375, 0.0, 3)))
        assertEquals("約1分", formatRemainingTime(choices[0].estimatedDurationSeconds(null)))
        assertEquals("約2分", formatRemainingTime(choices[1].estimatedDurationSeconds(null)))
    }

    @Test
    fun alternativesAreEverythingExceptTheSelected() {
        val opts = listOf(option("a", 1.0, 0.0, 3, true), option("b", 1.0, 0.0, 4))
        assertEquals(listOf(opts[1].points), alternativeLinesOf(opts, 0))
        assertEquals(listOf(opts[0].points), alternativeLinesOf(opts, 1)) // 選び直すと元の主線が灰色になる
    }

    @Test
    fun alternativesDropLinesTooShortToDraw() {
        val opts = listOf(option("a", 1.0, 0.0, 3), option("b", 1.0, 0.0, 1))
        assertEquals(emptyList<List<Pair<Double, Double>>>(), alternativeLinesOf(opts, 0))
    }

    @Test
    fun outOfRangeSelectionTreatsAllAsAlternatives() {
        val opts = listOf(option("a", 1.0, 0.0, 3), option("b", 1.0, 0.0, 3))
        assertEquals(2, alternativeLinesOf(opts, 5).size)
    }

    @Test
    fun formatRouteDistanceRoundsHalfUpConsistently() {
        assertEquals("0m", formatRouteDistance(0.0))
        assertEquals("450m", formatRouteDistance(450.4))
        assertEquals("451m", formatRouteDistance(450.5))
        assertEquals("1.0km", formatRouteDistance(999.6))
        assertEquals("11.2km", formatRouteDistance(11_240.0))
        assertEquals("11.3km", formatRouteDistance(11_250.0))
        assertEquals("11.3km", formatRouteDistance(11_270.0))
        assertEquals("--", formatRouteDistance(Double.NaN))
    }

    @Test
    fun choicesCompareFeaturesAndElevationDiffWhenAvailable() {
        val primary = option("route_primary", 10.0, 180.0, 3, primary = true, title = "主要サイクリングルート (推奨)")
        val alt = option("route_alternative", 10.6, 90.0, 3, primary = false, title = "裏道・平坦優先ルート")
        val choices = routeChoicesOf(listOf(primary, alt))

        assertEquals("最短", choices[0].featureTag)
        assertEquals("登りが90m多い", choices[0].elevationDiffText)
        assertEquals("10.0km ・ +180m", choices[0].detail)

        assertEquals("坂が少ない", choices[1].featureTag)
        assertEquals("登りが90m少ない", choices[1].elevationDiffText)
        assertEquals("10.6km ・ +90m", choices[1].detail)
    }

    @Test
    fun choicesHideElevationAndCompareDistanceWhenElevationMissing() {
        val primary = option("route_primary", 10.0, Double.NaN, 3, primary = true, title = "主要サイクリングルート (推奨)")
        val alt = option("route_alternative", 10.6, Double.NaN, 3, primary = false, title = "裏道・平坦優先ルート")
        val choices = routeChoicesOf(listOf(primary, alt))

        assertEquals("最短", choices[0].featureTag)
        assertNull(choices[0].elevationDiffText)
        assertEquals("10.0km", choices[0].detail)

        assertEquals("幹線を避ける", choices[1].featureTag)
        assertNull(choices[1].elevationDiffText)
        assertEquals("10.6km", choices[1].detail)
    }
}

class ElevationAxisTest {
    @org.junit.Test
    fun axisDoesNotGoNegativeForRoutesAboveSeaLevel() {
        org.junit.Assert.assertEquals(0.0, elevationAxisLow(4.0, 560.0), 1e-9)
        org.junit.Assert.assertEquals(90.0, elevationAxisLow(100.0, 100.0), 1e-9)
        org.junit.Assert.assertEquals(-4.0, elevationAxisLow(-3.0, 10.0), 1e-9) // 海面下の区間はそのまま
    }
}
