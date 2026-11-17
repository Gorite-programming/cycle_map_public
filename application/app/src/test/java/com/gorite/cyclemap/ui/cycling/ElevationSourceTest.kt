package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.data.filterRealDemSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationSourceTest {
    @Test
    fun labelsEstimatedDemAndMixedProfiles() {
        val estimated = listOf(ElevationSample(0.0, 10.0), ElevationSample(50.0, 12.0))
        val dem = listOf(
            ElevationSample(0.0, 10.0, ElevationSource.GSI_DEM),
            ElevationSample(50.0, 12.0, ElevationSource.GSI_DEM),
        )
        val mixed = listOf(
            ElevationSample(0.0, 10.0, ElevationSource.GSI_DEM),
            ElevationSample(50.0, 12.0, ElevationSource.ESTIMATED),
        )

        assertEquals("推定", elevationSourceLabel(estimated))
        assertEquals("DEM実測", elevationSourceLabel(dem))
        assertEquals("一部推定", elevationSourceLabel(mixed))
        assertEquals("標高未取得", elevationSourceLabel(emptyList()))
    }

    @Test
    fun syntheticFallbackSamplesAreMarkedEstimated() {
        val profile = buildRouteElevationProfile(emptyList(), 500.0)

        assertTrue(profile.isNotEmpty())
        assertEquals(setOf(ElevationSource.ESTIMATED), profile.map { it.source }.toSet())
    }

    @Test
    fun filterRealDemSamplesReturnsEmptyWhenNoRealData() {
        val allMissing = listOf(0.0 to null, 50.0 to null, 100.0 to null)
        assertTrue(filterRealDemSamples(allMissing).isEmpty())

        val singlePoint = listOf(0.0 to 12.3, 50.0 to null, 100.0 to null)
        assertTrue(filterRealDemSamples(singlePoint).isEmpty())

        // 全体の半分未満しか取れない場合は使わない
        val partial = listOf(0.0 to 10.0, 50.0 to 12.0, 100.0 to null, 150.0 to null, 200.0 to null)
        assertTrue(filterRealDemSamples(partial).isEmpty())
    }

    @Test
    fun filterRealDemSamplesKeepsValidDemSeries() {
        val valid = listOf(0.0 to 10.0, 50.0 to 12.5, 100.0 to null, 150.0 to 15.0)
        val filtered = filterRealDemSamples(valid)
        assertEquals(3, filtered.size)
        assertEquals(setOf(ElevationSource.GSI_DEM), filtered.map { it.source }.toSet())
    }

    @Test
    fun emptyProfileGivesNullElevationGainAndGrades() {
        val empty = emptyList<ElevationSample>()
        assertNull(elevationGain(empty, 1000.0))
        assertNull(elevationAt(empty, 0.0))
        assertNull(gradeAt(empty, 0.0))
        assertNull(forwardGradeAt(empty, 0.0))
        assertNull(gradeSummary(empty))
    }
}
