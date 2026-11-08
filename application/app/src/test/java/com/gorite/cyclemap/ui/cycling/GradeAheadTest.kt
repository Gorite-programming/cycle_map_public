package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.ui.map.gradeAheadText
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class GradeAheadTest {
    /** (距離m, 勾配%) の区間リストから、50m刻みの標高プロファイルを作る。 */
    private fun profile(vararg segments: Pair<Double, Double>): List<ElevationSample> {
        val out = ArrayList<ElevationSample>()
        var d = 0.0
        var e = 100.0
        out += ElevationSample(d, e)
        for ((len, grade) in segments) {
            var done = 0.0
            while (done < len) {
                val step = minOf(10.0, len - done)
                d += step; e += step * grade / 100.0; done += step
                out += ElevationSample(d, e)
            }
        }
        return out
    }

    @Test
    fun flatRouteHasNoGradeAhead() {
        assertNull(gradeAhead(profile(500.0 to 0.5), progressM = 0.0))
        assertEquals("この先は平坦", gradeAheadText(null))
    }

    @Test
    fun detectsClimbStartingAhead() {
        val ahead = gradeAhead(profile(150.0 to 0.0, 200.0 to 5.0, 150.0 to 0.0), progressM = 0.0)!!
        assertEquals(GradeKind.CLIMB, ahead.kind)
        assertEquals(5.0, ahead.gradePct, 1.0)
        assertTrue("startsInM=${ahead.startsInM}", ahead.startsInM in 100.0..175.0)
    }

    @Test
    fun singleFlatSampleDoesNotSplitClimb() {
        // 5%の坂の途中に 25m 刻みで1サンプルだけ平坦に見える箇所 (標高ノイズ) がある
        val ahead = gradeAhead(
            profile(100.0 to 0.0, 100.0 to 5.0, 20.0 to 0.0, 150.0 to 5.0, 200.0 to 0.0),
            progressM = 0.0,
        )!!
        assertEquals(GradeKind.CLIMB, ahead.kind)
        assertNotNull(ahead.lengthM)
        assertTrue("length=${ahead.lengthM}", ahead.lengthM!! > 200.0)
    }

    @Test
    fun detectsDescent() {
        val ahead = gradeAhead(profile(100.0 to 0.0, 250.0 to -6.0), progressM = 0.0)!!
        assertEquals(GradeKind.DESCENT, ahead.kind)
    }

    @Test
    fun insideClimbStartsAtZeroAndReportsRemainingLength() {
        val ahead = gradeAhead(profile(400.0 to 5.0, 200.0 to 0.0), progressM = 100.0)!!
        assertEquals(0.0, ahead.startsInM, 1e-9)
        assertNotNull(ahead.lengthM)
        assertTrue("length=${ahead.lengthM}", ahead.lengthM!! in 250.0..350.0)
    }

    @Test
    fun longClimbRunningPastLookaheadHasNoLength() {
        val ahead = gradeAhead(profile(1_000.0 to 4.0), progressM = 0.0, lookaheadM = 300.0)!!
        assertNull(ahead.lengthM)
    }

    @Test
    fun noProfileMeansNoAnswer() {
        assertNull(gradeAhead(emptyList(), 0.0))
        assertNull(remainingElevationGain(emptyList(), 0.0))
    }

    @Test
    fun remainingGainSubtractsGainAlreadyClimbed() {
        val p = profile(200.0 to 5.0, 200.0 to 0.0, 200.0 to 5.0) // 上り合計 20 m
        assertEquals(20.0, remainingElevationGain(p, 0.0)!!, 0.5)
        assertEquals(10.0, remainingElevationGain(p, 300.0)!!, 0.5)
        assertEquals(0.0, remainingElevationGain(p, 600.0)!!, 0.5)
    }

    @Test
    fun gradeTextReadsFromCurrentPosition() {
        assertEquals("150m先から 上り5%", gradeAheadText(GradeAhead(GradeKind.CLIMB, 5.2, 150.0, 200.0)))
        assertEquals("50m先から 下り6%", gradeAheadText(GradeAhead(GradeKind.DESCENT, 6.0, 50.0, null)))
        assertEquals("上り4% あと250m", gradeAheadText(GradeAhead(GradeKind.CLIMB, 4.0, 0.0, 250.0)))
        assertEquals("上り4% が続きます", gradeAheadText(GradeAhead(GradeKind.CLIMB, 4.0, 0.0, null)))
    }
}
