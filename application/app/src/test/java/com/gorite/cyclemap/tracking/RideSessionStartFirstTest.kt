package com.gorite.cyclemap.tracking

import org.junit.Assert.assertTrue
import org.junit.Test

class RideSessionStartFirstTest {
    @Test
    fun startBeforeFirstFixThenRun() {
        val s = RideSession()
        s.start()
        var t = 1_000_000L
        for (i in 0 until 60) {
            s.onLocation(t, 34.36, 132.40 + i * 2.7e-5, 5f, 5f)
            if (i % 2 == 0) s.snapshot(t - 300) // 画面の更新 (少し古い時刻)
            t += 1000
        }
        val snap = s.snapshot(t)
        println("SNAP $snap")
        assertTrue("distance=${snap.distanceM}", snap.distanceM > 100.0)
        assertTrue("moving=${snap.movingTimeS}", snap.movingTimeS > 30.0)
    }

    @Test
    fun positionJumpIsNotAddedToDistance() {
        val s = RideSession()
        var t = 1_000_000L
        for (i in 0 until 10) { s.onLocation(t, 34.36, 132.40 + i * 5e-5, 5f, 5f); t += 1000 }
        val before = s.snapshot(t).distanceM
        // 2 km 離れた別の測位源の点が 1 秒後に来る (約 2000 m/s)
        s.onLocation(t, 34.36, 132.42, 5f, 5f); t += 1000
        val after = s.snapshot(t).distanceM
        assertTrue("jump added: $before -> $after", after - before < 50.0)
    }
}
