package com.gorite.cyclemap.routing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 実走 (2026-11-21) で、停止中や精度の悪いときに位置が数十 m ぶれ、経路から外れたと誤判定されるおそれがあった。 */
class OffRouteJitterTest {

    private fun manager(scope: kotlinx.coroutines.CoroutineScope) = AdaptiveRerouteManager(
        scope = scope,
        rerouteDistanceThreshold = 30.0,
        consecutiveSamples = 3,
        routerAction = { _, _ -> null },
    ).also { it.setInitialRoute(listOf(1L, 2L, 3L)) }

    private fun AdaptiveRerouteManager.feed(distance: Double, accuracy: Double = 0.0, times: Int = 3) {
        repeat(times) { onLocationUpdate(distance, 4.0f, 10L, 30L, accuracy) }
    }

    @Test
    fun withoutAccuracyTheThresholdIsUnchanged() = runBlocking {
        val m = manager(this)
        m.feed(distance = 35.0)
        assertTrue(m.state.value.isOffRoute)
    }

    @Test
    fun poorAccuracyWidensTheOffRouteThreshold() = runBlocking {
        val m = manager(this)
        // 精度 40 m → 判定の距離は 60 m。45 m 離れて見えても、誤差の範囲なので外れたとはしない
        m.feed(distance = 45.0, accuracy = 40.0)
        assertFalse(m.state.value.isOffRoute)
    }

    @Test
    fun farOffRouteIsStillDetectedWithPoorAccuracy() = runBlocking {
        val m = manager(this)
        // 精度 40 m でも、90 m (閾値 60 m の 1.5 倍) 離れていれば、本当に外れたとみなす
        m.feed(distance = 90.0, accuracy = 40.0)
        assertTrue(m.state.value.isOffRoute)
    }

    @Test
    fun accuracyThresholdIsCappedSoAVeryBadFixCannotHideADetour() = runBlocking {
        val m = manager(this)
        // 精度 200 m でも、判定の距離の上限は 80 m。100 m 離れて 3 回続けば外れたとみなす
        m.feed(distance = 100.0, accuracy = 200.0)
        assertTrue(m.state.value.isOffRoute)
    }

    @Test
    fun stationaryIsDecidedFromTheMeasuredSpeed() {
        assertTrue(isStationaryForOffRoute(hasSpeed = true, speedMps = 0f))
        assertTrue(isStationaryForOffRoute(hasSpeed = true, speedMps = 0.49f))
        assertFalse(isStationaryForOffRoute(hasSpeed = true, speedMps = 0.5f))
        assertFalse(isStationaryForOffRoute(hasSpeed = true, speedMps = 4f))
        // 速度が無い測位は、停止とは決めつけない
        assertFalse(isStationaryForOffRoute(hasSpeed = false, speedMps = 0f))
    }
}
