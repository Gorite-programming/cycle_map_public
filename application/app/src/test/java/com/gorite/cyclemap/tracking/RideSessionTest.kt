package com.gorite.cyclemap.tracking

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class RideSessionTest {
    // 赤道上の経度差をメートルで指定する。
    private fun point(
        session: RideSession,
        timeMs: Long,
        meters: Double = 0.0,
        speed: Float? = 5f,
        accuracy: Float = 5f,
        altitude: Double? = null,
        pressure: Double? = null,
    ) = session.onLocation(timeMs, 0.0, Math.toDegrees(meters / 6_371_000.0), speed, accuracy, altitude, pressure)

    @Test
    fun stationaryGpsJitterDoesNotAddDistance() {
        val session = RideSession()
        repeat(61) { point(session, it * 1000L, (it % 3).toDouble(), speed = 0f) }
        assertEquals(RideState.IDLE, session.snapshot(60_000).state)
        assertEquals(0.0, session.snapshot(60_000).distanceM, 0.0)
    }

    @Test
    fun constantSpeedAccumulatesDistanceAndTime() {
        val session = RideSession()
        repeat(61) { point(session, it * 1000L, it * 5.0) }
        val snapshot = session.snapshot(60_000)
        assertEquals(RideState.RUNNING, snapshot.state)
        assertEquals(300.0, snapshot.distanceM, 0.01)
        assertEquals(60.0, snapshot.movingTimeS, 0.0)
        assertEquals(60.0, snapshot.elapsedS, 0.0)
        assertEquals(18.0, snapshot.speedKmh, 0.001)
        assertEquals(18.0, snapshot.averageKmh, 0.001)
    }

    @Test
    fun missingSpeedUsesDistanceAndTime() {
        val session = RideSession()
        repeat(61) { point(session, it * 1000L, it * 5.0, speed = null) }
        val snapshot = session.snapshot(60_000)
        assertEquals(300.0, snapshot.distanceM, 0.01)
        assertEquals(18.0, snapshot.speedKmh, 0.001)
        // 最初の移動点で走行を開始するので、走行時間は59秒。
        assertEquals(59.0, snapshot.movingTimeS, 0.001)
    }

    @Test
    fun currentAndMaximumSpeedUseLastThreeAcceptedSamples() {
        val session = RideSession()
        point(session, 0, speed = 1f)
        point(session, 1000, speed = 2f)
        point(session, 2000, speed = 3f)
        assertEquals(7.2, session.snapshot(2000).speedKmh, 0.001)
        point(session, 3000, speed = 4f)
        assertEquals(10.8, session.snapshot(3000).speedKmh, 0.001)
        assertEquals(10.8, session.snapshot(3000).maxKmh, 0.001)
    }

    @Test
    fun autoPauseAndResumeRespectExactBoundariesAfterSmoothing() {
        val session = RideSession()
        point(session, 0)
        point(session, 1000, speed = 0f)
        point(session, 2000, speed = 0f)
        point(session, 3000, speed = 0f) // 平滑化後も2km/h未満になった起点。
        point(session, 5999, speed = 0f)
        assertEquals(RideState.RUNNING, session.snapshot(5999).state)
        point(session, 6000, speed = 0f)
        assertEquals(RideState.PAUSED, session.snapshot(6000).state)
        assertEquals(0.0, session.snapshot(6000).speedKmh, 0.0)
        point(session, 7000)
        point(session, 7999)
        assertEquals(RideState.PAUSED, session.snapshot(7999).state)
        point(session, 8000)
        assertEquals(RideState.RUNNING, session.snapshot(8000).state)
        assertEquals(6.0, session.snapshot(8000).movingTimeS, 0.001)
        assertEquals(8.0, session.snapshot(8000).elapsedS, 0.001)
        assertEquals(7.0, session.snapshot(9000).movingTimeS, 0.001)
    }

    @Test
    fun interruptedLowSpeedDoesNotTriggerAutoPause() {
        val session = RideSession()
        session.start()
        point(session, 0, speed = 0f)
        point(session, 2999, speed = 0f)
        point(session, 3000, speed = 5f)
        point(session, 6000, speed = 0f)
        assertEquals(RideState.RUNNING, session.snapshot(6000).state)
    }

    @Test
    fun manualPauseRequiresManualStartAndFreezesStatistics() {
        val session = RideSession()
        point(session, 0)
        point(session, 1000, 5.0)
        session.pause()
        repeat(5) { point(session, 2000 + it * 1000L, 10.0 + it * 5) }
        val paused = session.snapshot(7000)
        assertEquals(RideState.PAUSED, paused.state)
        assertEquals(1.0, paused.movingTimeS, 0.0)
        assertEquals(7.0, paused.elapsedS, 0.0)
        assertEquals(5.0, paused.distanceM, 0.001)
        session.start(7000)
        assertEquals(RideState.RUNNING, session.snapshot(8000).state)
        assertEquals(2.0, session.snapshot(8000).movingTimeS, 0.0)
    }

    @Test
    fun manualStartBeforeFirstTimestampUsesNextSuppliedTime() {
        val session = RideSession()
        session.start()
        assertEquals(0.0, session.snapshot(99_000).elapsedS, 0.0)
        point(session, 100_000)
        assertEquals(1.0, session.snapshot(101_000).movingTimeS, 0.0)
    }

    @Test
    fun runningTimeCapsLongSignalGapBeforeAndAfterFirstFix() {
        val session = RideSession()
        point(session, 9_000)
        point(session, 10_000, 5.0)
        val beforeGap = session.snapshot(10_000)

        val duringGap = session.snapshot(610_000)
        assertEquals(RideState.RUNNING, duringGap.state)
        assertEquals(30.0, duringGap.movingTimeS - beforeGap.movingTimeS, 0.0)

        point(session, 610_000, 3_005.0)
        val afterGap = session.snapshot(610_000)
        assertEquals(duringGap.movingTimeS, afterGap.movingTimeS, 0.0)
        assertEquals(601.0, afterGap.elapsedS, 0.0)
        assertEquals(beforeGap.distanceM, afterGap.distanceM, 0.0)

        point(session, 611_000, 3_010.0)
        assertEquals(afterGap.movingTimeS + 1.0, session.snapshot(611_000).movingTimeS, 0.0)
    }

    @Test
    fun manualResumeWithoutTimestampWaitsForNextFixEvenAfterStateRoundTrip() {
        val session = RideSession()
        point(session, 0)
        session.pause(10_000)
        session.start()
        val restored = requireNotNull(RideSession.fromState(session.toState()))

        for (resumed in listOf(session, restored)) {
            val awaitingFix = resumed.snapshot(610_000)
            assertEquals(RideState.RUNNING, awaitingFix.state)
            assertEquals(10.0, awaitingFix.movingTimeS, 0.0)
            point(resumed, 610_000)
            assertEquals(10.0, resumed.snapshot(610_000).movingTimeS, 0.0)
            assertEquals(610.0, resumed.snapshot(610_000).elapsedS, 0.0)

            point(resumed, 611_000, 5.0)
            assertEquals(11.0, resumed.snapshot(611_000).movingTimeS, 0.0)
        }
    }

    @Test
    fun fixOlderThanTheRideCannotSetTheClockAfterResume() {
        val session = RideSession()
        point(session, 1_000)
        session.pause(10_000)
        session.start()
        val awaitingFix = session.toState()

        point(session, 999)

        assertEquals(awaitingFix, session.toState())
        point(session, 610_000)
        assertEquals(9.0, session.snapshot(610_000).movingTimeS, 0.0)
    }

    @Test
    fun poorAccuracyAndSignalGapsDoNotAddDistance() {
        val session = RideSession()
        point(session, 0)
        point(session, 1000, 100.0, accuracy = 50f)
        point(session, 2000, 10.0)
        // 精度の悪い飛んだ点は無視され、0 → 10 m の実際の移動だけが数えられる。
        assertEquals(10.0, session.snapshot(2000).distanceM, 0.001)
        point(session, 62_000, 310.0)
        assertEquals(10.0, session.snapshot(62_000).distanceM, 0.001)
        point(session, 63_000, 315.0)
        assertEquals(15.0, session.snapshot(63_000).distanceM, 0.001)
    }

    @Test
    fun distanceAcceptsExactAccuracySpeedAndTimeLimits() {
        val session = RideSession()
        session.start()
        point(session, 0, speed = 0.8f, accuracy = 20f)
        point(session, 4000, 3.2, speed = 0.8f, accuracy = 20f)
        assertEquals(3.2, session.snapshot(4000).distanceM, 0.001)
        val moving = RideSession()
        point(moving, 0, accuracy = 20f)
        point(moving, 30_000, 150.0, accuracy = 20f)
        assertEquals(150.0, moving.snapshot(30_000).distanceM, 0.001)
    }

    @Test
    fun singleSpeedSpikeIsExcludedBeforeSmoothing() {
        val session = RideSession()
        point(session, 0)
        point(session, 1000, 5.0)
        point(session, 2000, 1000.0, speed = (150.0 / 3.6).toFloat())
        point(session, 3000, 15.0)
        point(session, 4000, 20.0)
        assertEquals(18.0, session.snapshot(4000).maxKmh, 0.001)
        assertEquals(18.0, session.snapshot(4000).speedKmh, 0.001)
        // 飛んだ点は使わず、0 → 20 m の実際の移動 (計 20 m) だけを数える。
        assertEquals(20.0, session.snapshot(4000).distanceM, 0.001)
    }

    @Test
    fun gpsAltitudeNoiseDoesNotCountButSustainedClimbDoes() {
        val session = RideSession()
        repeat(20) { point(session, it * 1000L, altitude = 100.0 + (it % 3 - 1)) }
        assertEquals(0.0, session.snapshot(19_000).elevationGainM, 0.0)
        repeat(11) { point(session, 20_000 + it * 1000L, altitude = 100.0 + it) }
        repeat(5) { point(session, 31_000 + it * 1000L, altitude = 110.0) }
        // 3m未満の未確定分は次の登りまで保留される。
        assertEquals(10.0, session.snapshot(35_000).elevationGainM, 3.0)
    }

    @Test
    fun hourOfFlatGpsAltitudeNoiseAddsLessThanTenMeters() {
        val session = RideSession()
        repeat(3_600) {
            val noise = if (it % 2 == 0) -1.5 else 1.5
            point(session, it * 1000L, it * 5.0, altitude = 100.0 + noise)
        }
        assertTrue(session.snapshot(3_599_000).elevationGainM < 10.0)
    }

    @Test
    fun shortDescentDoesNotLowerTheElevationBaseline() {
        val session = RideSession()
        point(session, 0, altitude = 100.0)
        repeat(5) { point(session, (it + 1) * 1000L, altitude = 98.0) }
        repeat(5) { point(session, (it + 6) * 1000L, altitude = 102.0) }
        assertEquals(0.0, session.snapshot(10_000).elevationGainM, 0.0)
    }

    @Test
    fun steadyHundredMeterClimbAddsNearlyAllElevation() {
        val session = RideSession()
        repeat(101) { point(session, it * 1000L, it * 5.0, altitude = it.toDouble()) }
        assertTrue(session.snapshot(100_000).elevationGainM in 95.0..105.0)
    }

    @Test
    fun pressureUsesRelativeHeightAndIgnoresSmallChanges() {
        fun pressure(height: Double) = 1013.25 * (1.0 - height / 44330.0).pow(1.0 / 0.1903)
        val session = RideSession()
        point(session, 0, altitude = 100.0, pressure = pressure(0.0))
        repeat(10) { point(session, (it + 1) * 1000L, altitude = 1000.0, pressure = pressure((it % 2).toDouble())) }
        assertEquals(0.0, session.snapshot(10_000).elevationGainM, 0.0)
        point(session, 11_000, pressure = pressure(10.0))
        assertEquals(10.0, session.snapshot(11_000).elevationGainM, 0.001)
        point(session, 12_000, pressure = pressure(0.0))
        point(session, 13_000, pressure = pressure(5.0))
        assertEquals(15.0, session.snapshot(13_000).elevationGainM, 0.001)
    }

    @Test
    fun switchingAltitudeSourceDoesNotCountOffsetAsClimb() {
        val session = RideSession()
        point(session, 0, altitude = 1000.0)
        point(session, 1000, pressure = 1013.25)
        point(session, 2000, altitude = 1000.0)
        assertEquals(0.0, session.snapshot(2000).elevationGainM, 0.0)
    }

    @Test
    fun stateRoundTripPreservesStatisticsAndContinuedUpdates() {
        val original = RideSession()
        repeat(11) { point(original, it * 1000L, it * 5.0, altitude = it.toDouble()) }
        val expected = original.snapshot(10_000)
        val restored = requireNotNull(RideSession.fromState(original.toState()))
        assertEquals(expected, restored.snapshot(10_000))
        repeat(10) {
            point(original, 11_000 + it * 1000L, 55.0 + it * 5, altitude = 11.0 + it)
            point(restored, 11_000 + it * 1000L, 55.0 + it * 5, altitude = 11.0 + it)
        }
        assertEquals(original.snapshot(20_000), restored.snapshot(20_000))
    }

    @Test
    fun stateRoundTripPreservesManualPauseAndAutoPauseTimer() {
        val original = RideSession()
        original.start()
        point(original, 0, speed = 0f)
        point(original, 2000, speed = 0f)
        val restored = requireNotNull(RideSession.fromState(original.toState()))
        point(restored, 3000, speed = 0f)
        assertEquals(RideState.PAUSED, restored.snapshot(3000).state)
        original.pause()
        val manual = requireNotNull(RideSession.fromState(original.toState()))
        point(manual, 3000)
        point(manual, 5000)
        assertEquals(RideState.PAUSED, manual.snapshot(5000).state)
    }

    @Test
    fun corruptStateReturnsNullWithoutThrowing() {
        val valid = RideSession().toState()
        assertNotNull(RideSession.fromState(valid))
        assertNull(RideSession.fromState(emptyMap()))
        (valid.keys - "distanceOrigin").forEach { assertNull("missing $it", RideSession.fromState(valid - it)) }
        listOf("oops", "NaN", "Infinity", "-1").forEach {
            assertNull(RideSession.fromState(valid + ("distanceM" to it)))
        }
        assertNull(RideSession.fromState(valid + ("state" to "UNKNOWN")))
        assertNull(RideSession.fromState(valid + ("manualPause" to "yes")))
        assertNull(RideSession.fromState(valid + ("speeds" to "1,2,3,4")))
        assertNull(RideSession.fromState(valid + ("startedMs" to "100")))
    }

    @Test
    fun invalidAndOutOfOrderInputsDoNotPoisonStatistics() {
        val session = RideSession()
        point(session, 1000)
        point(session, -1, 100.0)
        point(session, 1000, 100.0)
        point(session, 999, 100.0)
        session.onLocation(2000, Double.NaN, 0.0, 5f, 1f)
        session.onLocation(2000, 0.0, Double.POSITIVE_INFINITY, 5f, 1f)
        point(session, 2000, 100.0, accuracy = Float.NaN)
        point(session, 2000, 100.0, accuracy = -1f)
        point(session, 2000, 5.0, altitude = Double.NaN, pressure = Double.POSITIVE_INFINITY)
        assertEquals(5.0, session.snapshot(2000).distanceM, 0.001)
        val before = session.snapshot(2000)
        assertEquals(before, session.snapshot(-1))
        assertEquals(before, session.snapshot(1000))
        point(session, 3000, 10.0, speed = Float.NaN)
        point(session, 4000, 15.0, speed = Float.POSITIVE_INFINITY)
        point(session, 5000, 20.0, speed = -1f)
        assertEquals(18.0, session.snapshot(5000).speedKmh, 0.001)
        assertEquals(0.0, session.snapshot(5000).elevationGainM, 0.0)
    }

    @Test
    fun resetClearsStatisticsAndAllHistory() {
        val session = RideSession()
        repeat(10) { point(session, it * 1000L, it * 5.0, pressure = 1013.25 - it) }
        session.pause()
        session.reset()
        assertEquals(RideSnapshot(), session.snapshot(10_000))
        val clean = RideSession()
        point(clean, 11_000, 0.0, pressure = 1000.0)
        point(session, 11_000, 0.0, pressure = 1000.0)
        assertEquals(clean.snapshot(11_000), session.snapshot(11_000))
    }
    @Test
    fun displayClockDoesNotDiscardDelayedFixOrMutateStoredTime() {
        val session = RideSession()
        point(session, 1000)
        val stored = session.toState()
        repeat(5) { session.snapshot(2000 + it * 1000L) }
        assertEquals(stored, session.toState())
        point(session, 1500, 5.0)
        val snapshot = session.snapshot(2000)
        assertEquals(5.0, snapshot.distanceM, 0.001)
        assertEquals(1.0, snapshot.movingTimeS, 0.0)
        val accepted = session.toState()
        point(session, 1499, 10.0)
        point(session, 1500, 10.0)
        assertEquals(accepted, session.toState())
        assertEquals(snapshot, session.snapshot(2000))
    }

    @Test
    fun explicitPauseFreezesMovingTimeWhileElapsedKeepsAdvancing() {
        val session = RideSession()
        point(session, 1000)
        session.pause(2000)
        val stored = session.toState()
        assertEquals(1.0, session.snapshot(5000).movingTimeS, 0.0)
        assertEquals(4.0, session.snapshot(5000).elapsedS, 0.0)
        assertEquals(stored, session.toState())
        session.start(6000)
        assertEquals(2.0, session.snapshot(7000).movingTimeS, 0.0)
    }

    @Test fun lowReportedSpeedAndPoorAccuracyCannotRaiseMaximumOrDistance() {
        val session = RideSession()
        session.start(0)
        repeat(20) { point(session, it * 1000L, (it % 3) * 2.0, speed = 0.4f) }
        point(session, 20_000, 25.0, speed = 10f, accuracy = 21f)
        val snapshot = session.snapshot(20_000)
        assertEquals(0.0, snapshot.distanceM, 0.0)
        assertEquals(0.0, snapshot.maxKmh, 0.0)
        assertEquals(0.0, snapshot.speedKmh, 0.0)
    }
    @Test fun jitterWithoutReportedSpeedDoesNotStartRide() {
        val session = RideSession()
        repeat(60) { point(session, it * 1000L, (it % 2) * 2.0, speed = null) }
        assertEquals(RideState.IDLE, session.snapshot(59_000).state)
        assertEquals(0.0, session.snapshot(59_000).maxKmh, 0.0)
        assertEquals(0.0, session.snapshot(59_000).distanceM, 0.0)
    }
    @Test fun stoppedFixImmediatelyZerosDisplayAndAddsNoResidualDistance() {
        val session = RideSession()
        point(session, 0)
        point(session, 1000, 5.0)
        point(session, 2000, 10.0, speed = 0f)
        assertEquals(0.0, session.snapshot(2000).speedKmh, 0.0)
        assertEquals(5.0, session.snapshot(2000).distanceM, 0.001)
        assertEquals(18.0, session.snapshot(2000).maxKmh, 0.001)
    }
    @Test fun frequentSmallMovingFixesAccumulateFromRetainedAnchor() {
        val session = RideSession()
        repeat(9) { point(session, it * 250L, it * 1.0, speed = 4f) }
        assertEquals(6.0, session.snapshot(2000).distanceM, 0.001)
        val restored = requireNotNull(RideSession.fromState(session.toState()))
        point(session, 2250, 9.0, speed = 4f)
        point(restored, 2250, 9.0, speed = 4f)
        assertEquals(9.0, session.snapshot(2250).distanceM, 0.001)
        assertEquals(session.snapshot(2250), restored.snapshot(2250))
    }
    @Test fun oldSavedStateWithoutDistanceAnchorStillLoads() {
        val session = RideSession()
        point(session, 0)
        assertNotNull(RideSession.fromState(session.toState() - "distanceOrigin"))
    }

    @Test fun frequentMovingFixesWithoutSensorSpeedStillAccumulateDistance() {
        val session = RideSession()
        repeat(21) { point(session, it * 250L, it * 1.1, speed = null) }
        val snapshot = session.snapshot(5000)
        assertTrue(snapshot.distanceM >= 19.0)
        assertTrue(snapshot.distanceM <= 22.0)
        assertTrue(snapshot.maxKmh > 10.0)
    }

}
