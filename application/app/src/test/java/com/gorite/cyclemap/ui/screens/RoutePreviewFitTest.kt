package com.gorite.cyclemap.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class RoutePreviewFitTest {
    @Test
    fun expandsTwoMeterBoundsToMinimumSpanAroundCenter() {
        val result = expandBoundsToMinSpan(35.0, 35.0 + 2.0 / 111_320.0, 139.0, 139.0)
        assertEquals(400.0, (result.maxLat - result.minLat) * 111_320.0, 1e-6)
        val centerLatitude = (result.maxLat + result.minLat) / 2.0
        assertEquals(400.0, (result.maxLon - result.minLon) * 111_320.0 * cos(Math.toRadians(centerLatitude)), 1e-6)
        assertEquals(35.0 + 1.0 / 111_320.0, (result.maxLat + result.minLat) / 2.0, 1e-12)
        assertEquals(139.0, (result.maxLon + result.minLon) / 2.0, 1e-12)
    }

    @Test
    fun leavesElevenAndThreeHundredThirtyFiveKilometerBoundsUnchanged() {
        val elevenKm = expandBoundsToMinSpan(35.0, 35.0 + 11_000.0 / 111_320.0, 139.0, 139.2)
        assertEquals(35.0, elevenKm.minLat, 0.0)
        assertEquals(35.0 + 11_000.0 / 111_320.0, elevenKm.maxLat, 0.0)
        assertEquals(139.0, elevenKm.minLon, 0.0)
        assertEquals(139.2, elevenKm.maxLon, 0.0)

        val threeHundredThirtyFiveKm = expandBoundsToMinSpan(33.0, 36.0, 130.0, 134.0)
        assertEquals(RoutePreviewBounds(33.0, 36.0, 130.0, 134.0), threeHundredThirtyFiveKm)
    }

    @Test
    fun longitudeSpanUsesLatitudeCosineCorrection() {
        val latitude = 60.0
        val longitudeSpanForTwoHundredMetersAtEquator = 200.0 / 111_320.0
        val result = expandBoundsToMinSpan(
            latitude, latitude,
            10.0, 10.0 + longitudeSpanForTwoHundredMetersAtEquator,
        )
        val actualMeters = (result.maxLon - result.minLon) * 111_320.0 * cos(Math.toRadians(latitude))
        assertEquals(400.0, actualMeters, 1e-6)
    }

    @Test
    fun identicalCoordinatesProduceAUsableNonZeroBounds() {
        val result = expandBoundsToMinSpan(35.0, 35.0, 139.0, 139.0)
        assertTrue(result.maxLat > result.minLat)
        assertTrue(result.maxLon > result.minLon)
        assertEquals(400.0, (result.maxLat - result.minLat) * 111_320.0, 1e-6)
    }

    @Test
    fun paddingGrowsWithScreenAndUsesNoMoreThanEightyPercentHeight() {
        val heights = (1800..3200 step 100).toList()
        val padding = heights.map { previewPaddingPx(it, density = 3f) }
        for (index in 1 until padding.size) {
            assertTrue(padding[index].top >= padding[index - 1].top)
            assertTrue(padding[index].bottom >= padding[index - 1].bottom)
        }
        heights.zip(padding).forEach { (height, item) ->
            assertTrue(item.top + item.bottom <= height * 0.80)
        }
        assertEquals(240, padding.first().side)
    }
}
