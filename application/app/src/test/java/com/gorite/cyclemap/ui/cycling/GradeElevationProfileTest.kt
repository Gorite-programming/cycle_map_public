package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.routing.RouteSegmentGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeElevationProfileTest {
    private fun seg(d: Double, g: Double?) = RouteSegmentGrade(d, g)

    @Test
    fun accumulatesGradeIntoElevation() {
        // 1000m を 5% で登ると 50m、続く 1000m を -2% で下ると -20m
        val p = buildGradeElevationProfile(listOf(seg(1000.0, 5.0), seg(1000.0, -2.0)), startElevationM = 100.0)
        assertEquals(100.0, p.first().elevationM, 1e-6)
        assertEquals(150.0, p.first { it.distanceM >= 1000.0 - 1e-6 }.elevationM, 1.0)
        assertEquals(130.0, p.last().elevationM, 1e-6)
        assertEquals(2000.0, p.last().distanceM, 1e-6)
        assertTrue(p.all { it.source == ElevationSource.GRAPH_GRADE })
    }

    @Test
    fun unknownStartElevationGivesRelativeProfileWithMinimumZero() {
        val p = buildGradeElevationProfile(listOf(seg(1000.0, -5.0), seg(1000.0, 5.0)), startElevationM = null)
        assertEquals(0.0, p.minOf { it.elevationM }, 1e-6)
        assertEquals(50.0, p.first().elevationM, 1e-6) // 先に 50m 下るので出発地が最も高い
        assertTrue(p.all { it.source == ElevationSource.GRAPH_GRADE_RELATIVE })
        assertEquals("勾配算出・相対", elevationSourceLabel(p))
    }

    @Test
    fun unknownGradeIsTreatedAsFlat() {
        val p = buildGradeElevationProfile(listOf(seg(500.0, null), seg(500.0, null)), startElevationM = 10.0)
        assertTrue(p.all { kotlin.math.abs(it.elevationM - 10.0) < 1e-9 })
    }

    @Test
    fun gainCountsOnlyClimbs() {
        val p = buildGradeElevationProfile(listOf(seg(1000.0, 5.0), seg(1000.0, -5.0), seg(1000.0, 3.0)), startElevationM = null)
        // 登り 50m + 30m = 80m (間引きの誤差を許す)
        assertEquals(80.0, elevationGain(p, 3000.0)!!, 3.0)
    }

    @Test
    fun emptyOrZeroLengthRouteGivesEmptyProfile() {
        assertTrue(buildGradeElevationProfile(emptyList(), 0.0).isEmpty())
        assertTrue(buildGradeElevationProfile(listOf(seg(0.0, 3.0)), 0.0).isEmpty())
    }

    @Test
    fun anchorUsesAverageOffsetBetweenNodeElevationsAndIntegratedGrade() {
        // 勾配の積み上げ: 0, +50, +30 (1000m を 5%、続く 1000m を -2%)。絶対標高は 100, 150, 130 に近い
        val segs = listOf(seg(1000.0, 5.0), seg(1000.0, -2.0))
        assertEquals(100.0, anchoredStartElevation(segs, listOf(100.0, 150.0, 130.0))!!, 1e-9)
        // DEM のばらつきで各ノードが少しずれても、差の平均で出発地を決める (差 99, 101, 100 の平均 = 100)
        assertEquals(100.0, anchoredStartElevation(segs, listOf(99.0, 151.0, 130.0))!!, 1e-9)
        // 一部が不明でも残りで決める
        assertEquals(100.0, anchoredStartElevation(segs, listOf(null, 150.0, null))!!, 1e-9)
    }

    @Test
    fun anchorIsNullWhenNoAbsoluteElevationOrSizeMismatch() {
        val segs = listOf(seg(1000.0, 5.0))
        assertEquals(null, anchoredStartElevation(segs, listOf(null, null)))
        assertEquals(null, anchoredStartElevation(segs, listOf(1.0)))
        assertEquals(null, anchoredStartElevation(emptyList(), listOf(1.0)))
    }
}
