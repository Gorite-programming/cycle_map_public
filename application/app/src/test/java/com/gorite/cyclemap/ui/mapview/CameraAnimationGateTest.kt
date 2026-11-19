package com.gorite.cyclemap.ui.mapview

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAnimationGateTest {
    @After fun tearDown() = CameraAnimationGate.release()

    @Test
    fun holdsFollowDuringAnimationAndReleasesAfterwards() {
        CameraAnimationGate.hold(durationMs = 600, nowMs = 1_000)
        assertTrue(CameraAnimationGate.isBusy(nowMs = 1_500))   // アニメーション中は追従しない
        assertTrue(CameraAnimationGate.isBusy(nowMs = 1_700))   // 余裕の分も待つ
        assertFalse(CameraAnimationGate.isBusy(nowMs = 1_800))  // 通知が来なくても、時間を過ぎたら追従に戻る
    }

    @Test
    fun releaseEndsHoldImmediately() {
        CameraAnimationGate.hold(durationMs = 600, nowMs = 1_000)
        CameraAnimationGate.release()
        assertFalse(CameraAnimationGate.isBusy(nowMs = 1_001))
    }

    @Test
    fun longerHoldIsNotShortenedByLaterShortOne() {
        CameraAnimationGate.hold(durationMs = 2_000, nowMs = 1_000)
        CameraAnimationGate.hold(durationMs = 100, nowMs = 1_100)
        assertTrue(CameraAnimationGate.isBusy(nowMs = 2_500))
    }
}
