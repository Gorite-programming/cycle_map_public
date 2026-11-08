package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.contract.RouteOption
import com.gorite.cyclemap.contract.TravelModeType
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteChoicesTest {
    private fun option(id: String, km: Double, gain: Double, pts: Int, primary: Boolean = false) = RouteOption(
        id = id, title = id, travelMode = TravelModeType.Bicycle, distanceMeters = km * 1000.0,
        durationSeconds = 0.0, elevationGainMeters = gain,
        points = List(pts) { 34.0 + it * 0.001 to 132.0 }, isPrimary = primary,
    )

    @Test
    fun choicesShowDistanceAndGain() {
        val c = routeChoicesOf(listOf(option("a", 9.68, 175.4, 3, true), option("b", 10.04, 0.0, 3)))
        assertEquals("ルート1", c[0].label)
        assertEquals("9.7km ・ +175m", c[0].detail)
        assertEquals("10.0km", c[1].detail) // 獲得標高が 0 のときは出さない
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
}
