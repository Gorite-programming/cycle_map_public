package com.gorite.cyclemap.ui.mapview

import com.gorite.cyclemap.power.CameraMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSmoothingTest {
    private fun dist(a: LocationGlide.Position, lat: Double, lon: Double) =
        CameraMotionPolicy.distanceMeters(a.latitude, a.longitude, lat, lon)

    @Test
    fun noPositionBeforeFirstFix() {
        assertNull(LocationGlide().position(0L))
    }

    @Test
    fun firstFixIsShownImmediately() {
        val g = LocationGlide()
        g.onFix(34.0, 131.0, null, null, 1_000L)
        val p = g.position(1_000L)!!
        assertEquals(34.0, p.latitude, 1e-9)
        assertEquals(131.0, p.longitude, 1e-9)
    }

    @Test
    fun glidesBetweenFixesWithoutJumping() {
        val g = LocationGlide()
        g.onFix(34.0, 131.0, null, null, 0L)
        // 1秒後に北へ約11m進んだ位置が届く (静止扱い: 推測なし)
        g.onFix(34.0001, 131.0, null, null, 1_000L)
        val first = g.position(1_000L)!!
        // 届いた直後はまだ前の位置 (跳ばない)
        assertEquals(34.0, first.latitude, 1e-9)
        val mid = g.position(1_500L)!!
        assertTrue(mid.latitude > 34.0 && mid.latitude < 34.0001)
        assertEquals(34.00005, mid.latitude, 1e-6)
    }

    @Test
    fun movesMonotonicallyAtSteadyPace() {
        val g = LocationGlide()
        g.onFix(34.0, 131.0, null, null, 0L)
        g.onFix(34.0001, 131.0, null, null, 1_000L)
        var prev = g.position(1_000L)!!.latitude
        for (t in 1_016L..1_990L step 16L) {
            val cur = g.position(t)!!.latitude
            assertTrue("t=$t", cur >= prev)
            assertTrue("step too large at t=$t", cur - prev < 0.00001)
            prev = cur
        }
    }

    @Test
    fun continuesAlongCourseWhenNextFixIsLate() {
        val g = LocationGlide()
        g.onFix(34.0, 131.0, 5f, 0f, 0L) // 北向き 5m/s
        val atEnd = g.position(1_000L)!!
        val later = g.position(2_000L)!!
        assertTrue("推測で北へ進むはず", later.latitude > atEnd.latitude)
        // ただし推測は最大1.5秒まで (5m/s * 1.5s = 7.5m)
        val capped = g.position(10_000L)!!
        val moved = dist(capped, 34.0, 131.0)
        assertTrue("moved=$moved", moved < 12.0)
    }

    @Test
    fun extrapolationStaysWithinLimitWhenGpsStalls() {
        val g = LocationGlide()
        // 北へ時速36km (10 m/s) で走行中に GPS が途絶える
        g.onFix(34.0, 131.0, 10f, 0f, 0L)
        val stalled = g.position(30_000L)!!
        // 実測位置からの先読みの合計は maxExtrapolateMs (1.5秒) 分 = 15m まで
        assertTrue("ahead=${dist(stalled, 34.0, 131.0)}", dist(stalled, 34.0, 131.0) <= 15.5)
    }

    @Test
    fun largeJumpSnapsInsteadOfGliding() {
        val g = LocationGlide()
        g.onFix(34.0, 131.0, null, null, 0L)
        g.onFix(34.01, 131.0, null, null, 1_000L) // 約1.1km: リルート・測位復帰
        val p = g.position(1_000L)!!
        assertEquals(34.01, p.latitude, 1e-9)
    }

    @Test
    fun resetClearsPosition() {
        val g = LocationGlide()
        g.onFix(34.0, 131.0, null, null, 0L)
        g.reset()
        assertNull(g.position(10L))
    }

    @Test
    fun shortestDeltaCrossesNorth() {
        assertEquals(20f, Angles.shortestDelta(350f, 10f), 1e-4f)
        assertEquals(-20f, Angles.shortestDelta(10f, 350f), 1e-4f)
        assertEquals(90f, Angles.shortestDelta(0f, 90f), 1e-4f)
    }

    @Test
    fun followerApproachesTargetWithoutOvershoot() {
        val f = AngleFollower(timeConstantSec = 0.12f)
        f.snap(0f)
        var prev = 0f
        repeat(120) {
            val cur = f.step(90f, 1f / 60f)
            assertTrue(cur >= prev)
            assertTrue(cur <= 90f)
            prev = cur
        }
        assertEquals(90f, prev, 0.5f)
    }

    @Test
    fun followerTakesTheShortWayAcrossNorth() {
        val f = AngleFollower()
        f.snap(350f)
        val next = f.step(10f, 1f / 60f)
        // 350 → 10 は時計回りに20°。逆回りの 340° 回転はしない
        assertTrue("next=$next", next > 350f || next < 10f)
        assertTrue(next != 350f)
    }

    @Test
    fun followerSettlesExactlyOnTarget() {
        val f = AngleFollower()
        f.snap(100f)
        repeat(300) { f.step(101f, 1f / 60f) }
        assertEquals(101f, f.current, 1e-4f)
        assertTrue(f.isSettled(101f))
        assertNotNull(f.current)
    }

    @Test
    fun followerIsFrameRateIndependent() {
        // 120Hz でも 60Hz でも、同じ時間で同じ位置に近づく
        val a = AngleFollower(); a.snap(0f)
        val b = AngleFollower(); b.snap(0f)
        repeat(30) { a.step(60f, 1f / 60f) }
        repeat(60) { b.step(60f, 1f / 120f) }
        assertEquals(a.current, b.current, 0.5f)
    }
}

class ZoomFollowerTest {
    @Test
    fun zoomsOneLevelSmoothly() {
        val z = ZoomFollower()
        z.nudge(1.0, actual = 15.0, min = 3.0, max = 20.0, inProgress = false)
        var prev = 15.0
        repeat(120) {
            val cur = z.step(1f / 60f)
            assertTrue(cur >= prev && cur <= 16.0)
            prev = cur
        }
        assertEquals(16.0, prev, 1e-9)
        assertTrue(z.isSettled)
    }

    @Test
    fun repeatedPressesAccumulateWhileRunning() {
        val z = ZoomFollower()
        z.nudge(1.0, 15.0, 3.0, 20.0, inProgress = false)
        z.step(1f / 60f)
        z.nudge(1.0, 15.2, 3.0, 20.0, inProgress = true)
        repeat(200) { z.step(1f / 60f) }
        assertEquals(17.0, z.current, 1e-9)
    }

    @Test
    fun clampedToZoomRange() {
        val z = ZoomFollower()
        z.nudge(1.0, 19.5, 3.0, 20.0, inProgress = false)
        assertEquals(20.0, z.target, 1e-9)
        z.nudge(-1.0, 3.2, 3.0, 20.0, inProgress = false)
        assertEquals(3.0, z.target, 1e-9)
    }
}
