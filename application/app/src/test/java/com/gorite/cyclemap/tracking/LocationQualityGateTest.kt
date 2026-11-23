package com.gorite.cyclemap.tracking

import com.gorite.cyclemap.utils.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityGateTest {
    private val baseLat = 35.0
    private val baseLon = 135.0
    private fun nanos(seconds: Double) = (seconds * 1_000_000_000L).toLong()

    /** 北へ [meters] m 動いた位置。 */
    private fun fix(seconds: Double, northMeters: Double = 0.0, eastMeters: Double = 0.0, accuracy: Float? = 8f, speed: Float? = null, speedAccuracy: Float? = null) = GateFix(
        latitude = baseLat + northMeters / 110_574.0,
        longitude = baseLon + eastMeters / (111_320.0 * Math.cos(Math.toRadians(baseLat))),
        elapsedNanos = nanos(seconds),
        accuracyMeters = accuracy,
        speedMps = speed,
        speedAccuracyMps = speedAccuracy,
    )

    @Test
    fun firstFixIsAccepted() {
        assertEquals(GateVerdict.ACCEPT, LocationQualityGate().evaluate(fix(0.0)))
    }

    @Test
    fun poorAccuracyIsRejectedAndDoesNotMoveTheAnchor() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        assertEquals(GateVerdict.REJECT_INACCURATE, gate.evaluate(fix(1.0, northMeters = 500.0, accuracy = 150f)))
        // 精度の悪い測位を捨てても、次の普通の測位は、元の位置との差で判定される
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(2.0, northMeters = 6.0)))
    }

    @Test
    fun normalCyclingKeepsBeingAccepted() {
        val gate = LocationQualityGate()
        var north = 0.0
        for (t in 0..20) {
            assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(t.toDouble(), northMeters = north, speed = 5f)))
            north += 5.0
        }
    }

    @Test
    fun dopplerStopHoldsThePositionEvenWhenTheFixJumps() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0, speed = 4f))
        // 停止中: ドップラーは 0 のまま、位置だけが 65 m 往復する
        assertEquals(GateVerdict.STATIONARY, gate.evaluate(fix(7.0, eastMeters = 65.0, speed = 0f, speedAccuracy = 0.4f)))
        assertEquals(GateVerdict.STATIONARY, gate.evaluate(fix(14.0, eastMeters = 0.0, speed = 0f, speedAccuracy = 0.4f)))
        assertEquals(GateVerdict.STATIONARY, gate.evaluate(fix(21.0, eastMeters = 70.0, speed = 0.1f)))
    }

    @Test
    fun stationaryFarFromTheAnchorReanchorsInsteadOfFreezing() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        // ドップラーが 0 のまま 300 m 先 (本当に動いたのにドップラーが当てにならない) → 位置を信じて採用し直す
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(30.0, northMeters = 300.0, speed = 0f)))
    }

    @Test
    fun untrustedDopplerIsIgnoredForTheStopDecision() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        // 速度の精度が悪い (3 m/s) ドップラーの 0 は、停止の根拠にしない
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(7.0, northMeters = 30.0, speed = 0f, speedAccuracy = 3f)))
    }

    @Test
    fun jumpThatContradictsTheDopplerSpeedIsRejected() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0, speed = 2f))
        // ドップラーは 2 m/s なのに、7 秒で 70 m (10 m/s) 移動した位置 → 飛び
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(7.0, northMeters = 70.0, speed = 2f)))
    }

    @Test
    fun legitimateFastDescentIsAcceptedWhenTheDopplerAgrees() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0, speed = 14f))
        // 14 m/s (50 km/h) で 5 秒走った移動 (70 m) は、ドップラーと一致するので採用
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(5.0, northMeters = 70.0, speed = 14f)))
    }

    @Test
    fun withoutDopplerAnImpossibleSpeedIsRejected() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        // 2 秒で 100 m (50 m/s) は、自転車にはありえない
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(2.0, northMeters = 100.0)))
    }

    @Test
    fun withoutDopplerPlausibleSpeedsAreAccepted() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        // 7 秒で 60 m (8.6 m/s、約 31 km/h) はありうるので、ドップラーが無ければ採用する
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(7.0, northMeters = 60.0)))
    }

    @Test
    fun longGapBeforeTheNextFixIsNotAJump() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        // 60 秒の途切れのあとに 600 m 離れた位置 (10 m/s) → 途切れの後の再取得なので、飛びとは扱わない
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(60.0, northMeters = 600.0)))
    }

    @Test
    fun longGapAllowsTheFirstFixEvenWhenDopplerShowsDeceleration() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0, speed = 10f))
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(30.0, northMeters = 300.0, speed = 2f)))
    }

    @Test
    fun scatteredJumpsAreRejectedUntilThreeConsecutiveFixesAgree() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(1.0, eastMeters = 150.0)))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(2.0, eastMeters = -200.0)))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(3.0, northMeters = 350.0)))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(4.0, northMeters = 355.0)))
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(5.0, northMeters = 360.0)))
    }

    @Test
    fun repeatedJumpsReanchorSoTheGateNeverLocksOut() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(0.0))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(1.0, northMeters = 100.0)))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(2.0, northMeters = 101.0)))
        // 3 回続けて「飛び」とされた先は、本当の位置とみなして採用し直す
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(3.0, northMeters = 102.0)))
        assertEquals(GateVerdict.ACCEPT, gate.evaluate(fix(4.0, northMeters = 106.0)))
    }

    @Test
    fun staleOrOutOfOrderFixIsRejected() {
        val gate = LocationQualityGate()
        gate.evaluate(fix(10.0))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(10.0, northMeters = 1.0)))
        assertEquals(GateVerdict.REJECT_JUMP, gate.evaluate(fix(5.0, northMeters = 1.0)))
    }

    /**
     * 実走 (約 7 分・ウォッチは 1.78 km) の GPS のぶれを、座標だけ平行移動した再現データで流す。
     * ゲートなしでは、停止中の往復が距離に約 460 m 加わり、2.45 km になる。
     * ウォッチが一時停止 50 秒と記録した区間に、ドップラー 0 を与えたうえで、距離の水増しが大きく減ることを確かめる。
     */
    @Test
    fun replayOfARealRideRemovesTheStopDriftFromTheDistance() {
        val rows = javaClass.getResourceAsStream("/ride_gps_noise_shifted.csv")!!.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("t_s") }
            .map { line ->
                val c = line.split(',')
                GateFix(
                    latitude = c[1].toDouble(),
                    longitude = c[2].toDouble(),
                    elapsedNanos = nanos(c[0].toDouble()),
                    accuracyMeters = 10f,
                    speedMps = c.getOrNull(3)?.takeIf { it.isNotBlank() }?.toFloat(),
                    speedAccuracyMps = 0.4f,
                )
            }
        assertEquals(65, rows.size)

        var rawMeters = 0.0
        for (i in 1 until rows.size) rawMeters += haversineMeters(rows[i - 1].latitude, rows[i - 1].longitude, rows[i].latitude, rows[i].longitude)

        val gate = LocationQualityGate()
        var gatedMeters = 0.0
        var last: GateFix? = null
        var stationary = 0
        for (fix in rows) {
            when (gate.evaluate(fix)) {
                GateVerdict.ACCEPT -> {
                    last?.let { gatedMeters += haversineMeters(it.latitude, it.longitude, fix.latitude, fix.longitude) }
                    last = fix
                }
                GateVerdict.STATIONARY -> stationary++
                else -> Unit
            }
        }

        assertTrue("raw=$rawMeters", rawMeters in 2_400.0..2_500.0)
        assertEquals(10, stationary)
        // ウォッチの距離 (1,780 m) に対して、元は +38%。停止中の往復を止めると、+12% 前後まで下がる
        assertTrue("gated=$gatedMeters", gatedMeters < 2_100.0)
        assertTrue("gated=$gatedMeters raw=$rawMeters", rawMeters - gatedMeters > 350.0)
    }
}
