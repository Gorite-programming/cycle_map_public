package com.gorite.cyclemap.tracking

import com.gorite.cyclemap.routing.haversineMeters
import kotlin.math.pow

/** これを超える見かけの速さ (m/s、約 180 km/h) は位置の飛びとして扱う。 */
private const val MAX_PLAUSIBLE_MPS = 50.0
private const val MAX_FIX_INTERVAL_MS = 30_000L

/** Android に依存しない走行統計。時刻は位置更新または表示更新から受け取る。 */
class RideSession {
    private var state = RideState.IDLE
    private var manualPause = false
    private var clockMs: Long? = null
    private var startedMs: Long? = null
    private var movingTimeS = 0.0
    private var distanceM = 0.0
    private var maxKmh = 0.0
    private var elevation = ElevationGainAccumulator()
    private var lowSinceMs: Long? = null
    private var highSinceMs: Long? = null
    private var previous: Fix? = null
    private var distanceOrigin: Fix? = null
    private val speeds = ArrayDeque<Double>()
    private var referencePressure: Double? = null
    private var pressureSource = false

    private data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

    @Synchronized
    fun onLocation(
        timeMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float?,
        accuracyM: Float,
        altitudeM: Double? = null,
        pressureHpa: Double? = null,
    ) {
        if (timeMs < 0 || !lat.isFinite() || lat !in -90.0..90.0 ||
            !lon.isFinite() || lon !in -180.0..180.0 ||
            !accuracyM.isFinite() || accuracyM < 0f
        ) return
        if (clockMs == null && timeMs < (startedMs ?: 0L)) return
        val old = previous
        // 遅延・重複した位置で距離や平滑化の履歴を巻き戻さない。
        if (old != null && timeMs <= old.timeMs) return
        // Reject poor fixes before they enter speed smoothing or maximum-speed statistics.
        if (!RideMotionPolicy.acceptsAccuracy(accuracyM)) return
        val dtMs = old?.let { timeMs - it.timeMs }
        val stepM = old?.let { haversineMeters(it.lat, it.lon, lat, lon) }
        // 位置の飛び (GPS の誤差・別の測位源への切り替え): 時間の割に離れすぎた点は、距離にも速度にも使わない。
        // 本当に移動していたなら、30 秒以上たてば次の点が起点になる (距離は足さない)。
        if (dtMs != null && dtMs in 1..MAX_FIX_INTERVAL_MS && stepM != null &&
            stepM > MAX_PLAUSIBLE_MPS * dtMs / 1000.0 + 30.0
        ) return
        val reportedSpeed = speedMps?.takeIf { it.isFinite() && it >= 0f }
        val origin = distanceOrigin ?: old
        val originDtMs = origin?.let { timeMs - it.timeMs }
        val displacementM = origin?.let { haversineMeters(it.lat, it.lon, lat, lon) }
        // With no sensor speed, infer motion from the retained anchor too. Otherwise
        // frequent, genuine updates under 3 m would all be mistaken for stops.
        val rawSpeed = reportedSpeed?.toDouble()
            ?: if (originDtMs != null && originDtMs in 1..MAX_FIX_INTERVAL_MS && displacementM != null) {
                displacementM / (originDtMs / 1000.0)
            } else 0.0
        if (dtMs != null && dtMs > MAX_FIX_INTERVAL_MS) {
            speeds.clear()
            lowSinceMs = null
            highSinceMs = null
        }
        // 飛びは平均にも混ぜず、その位置を次の距離計算の起点にもしない。
        if (!rawSpeed.isFinite() || rawSpeed * 3.6 > 120.0) {
            previous = null
            distanceOrigin = null
            lowSinceMs = null
            highSinceMs = null
            return
        }
        advance(timeMs)
        // Between confirmed displacements retain the last inferred speed while the
        // anchor still implies movement; do not dilute it with artificial zeroes.
        val motionSpeed = RideMotionPolicy.speedMps(rawSpeed, displacementM, reportedSpeed != null, speeds.lastOrNull() ?: 0.0)
        speeds.addLast(motionSpeed)
        if (speeds.size > 3) speeds.removeFirst()
        val speed = speeds.average()
        val wasPaused = state == RideState.PAUSED
        updateAutoPause(timeMs, speed * 3.6)
        val fix = Fix(timeMs, lat, lon, accuracyM)
        if (!wasPaused && state == RideState.RUNNING && motionSpeed >= RideMotionPolicy.MIN_SPEED_MPS &&
            origin != null && dtMs != null && dtMs in 1..MAX_FIX_INTERVAL_MS
        ) {
            val distance = haversineMeters(origin.lat, origin.lon, lat, lon)
            if (RideMotionPolicy.acceptsDistance(distance, motionSpeed)) {
                distanceM += distance
                distanceOrigin = fix
            } else {
                // Retain an anchor so genuine sub-3 m updates can accumulate displacement.
                distanceOrigin = origin
            }
        } else {
            distanceOrigin = if (reportedSpeed == null && origin != null &&
                originDtMs != null && originDtMs in 1..MAX_FIX_INTERVAL_MS &&
                displacementM != null && displacementM < RideMotionPolicy.MIN_DISTANCE_M
            ) origin else fix
        }
        if (state == RideState.RUNNING && motionSpeed >= RideMotionPolicy.MIN_SPEED_MPS) {
            maxKmh = maxOf(maxKmh, speed * 3.6)
        }
        if (wasPaused || state != RideState.RUNNING) elevation.resetBaseline()
        updateElevation(altitudeM, pressureHpa)
        previous = fix
    }

    private fun updateAutoPause(timeMs: Long, kmh: Double) {
        if (manualPause) return
        if (kmh >= 2.0) {
            lowSinceMs = null
            if (state == RideState.IDLE) {
                state = RideState.RUNNING
                if (startedMs == null) startedMs = timeMs
            } else if (state == RideState.PAUSED) {
                if (highSinceMs == null) highSinceMs = timeMs
                if (timeMs - highSinceMs!! >= 1000) state = RideState.RUNNING
            }
        } else {
            highSinceMs = null
            if (state == RideState.RUNNING) {
                if (lowSinceMs == null) lowSinceMs = timeMs
                if (timeMs - lowSinceMs!! >= 3000) state = RideState.PAUSED
            }
        }
    }

    private fun updateElevation(altitudeM: Double?, pressureHpa: Double?) {
        val usePressure = pressureHpa != null && pressureHpa.isFinite() && pressureHpa > 0.0
        val height = if (usePressure) {
            if (referencePressure == null) referencePressure = pressureHpa
            44330.0 * (1.0 - (pressureHpa!! / referencePressure!!).pow(0.1903))
        } else altitudeM ?: return
        if (!height.isFinite()) return
        // 気圧高度と絶対高度を切り替えた差は登りに数えない。
        if (pressureSource != usePressure) elevation.resetBaseline()
        // GPS の平滑化窓は GPX と共通。気圧高度は従来どおり直接使う。
        elevation.add(height, smooth = !usePressure)
        pressureSource = usePressure
    }

    private fun movingIntervalS(timeMs: Long): Double =
        clockMs?.let { (timeMs - it).coerceIn(0L, MAX_FIX_INTERVAL_MS) / 1000.0 } ?: 0.0

    private fun advance(timeMs: Long) {
        if (timeMs < (clockMs ?: startedMs ?: 0L)) return
        if (state == RideState.RUNNING) {
            if (startedMs == null) startedMs = timeMs
            movingTimeS += movingIntervalS(timeMs)
        }
        clockMs = timeMs
    }

    @Synchronized
    fun snapshot(nowMs: Long): RideSnapshot {
        val displayMs = maxOf(nowMs, clockMs ?: nowMs)
        val displayedMovingTimeS = movingTimeS + if (state == RideState.RUNNING) {
            movingIntervalS(displayMs)
        } else 0.0
        return RideSnapshot(
            state = state,
            speedKmh = if (state == RideState.RUNNING && speeds.isNotEmpty() && speeds.last() >= RideMotionPolicy.MIN_SPEED_MPS) speeds.average() * 3.6 else 0.0,
            distanceM = distanceM,
            movingTimeS = displayedMovingTimeS,
            elapsedS = startedMs?.let { (displayMs - it).coerceAtLeast(0) / 1000.0 } ?: 0.0,
            averageKmh = if (displayedMovingTimeS > 0.0) distanceM / displayedMovingTimeS * 3.6 else 0.0,
            maxKmh = maxKmh,
            elevationGainM = elevation.gainMeters,
        )
    }

    @Synchronized
    fun start(nowMs: Long? = null) {
        nowMs?.let { advance(it) }
        if (state == RideState.RUNNING) return
        // 時刻が渡されなければ、再開後の最初の測位を新しい起点にする。
        if (nowMs == null) clockMs = null
        state = RideState.RUNNING
        manualPause = false
        if (startedMs == null) startedMs = clockMs
        lowSinceMs = null
        highSinceMs = null
        previous = null
        distanceOrigin = null
        speeds.clear()
        elevation.resetBaseline()
    }

    @Synchronized
    fun pause(nowMs: Long? = clockMs) {
        nowMs?.let { advance(it) }
        state = RideState.PAUSED
        manualPause = true
        lowSinceMs = null
        highSinceMs = null
        previous = null
        distanceOrigin = null
        speeds.clear()
        elevation.resetBaseline()
    }

    @Synchronized
    fun reset() {
        state = RideState.IDLE
        manualPause = false
        clockMs = null
        startedMs = null
        movingTimeS = 0.0
        distanceM = 0.0
        maxKmh = 0.0
        elevation = ElevationGainAccumulator()
        lowSinceMs = null
        highSinceMs = null
        previous = null
        distanceOrigin = null
        speeds.clear()
        referencePressure = null
        pressureSource = false
    }

    @Synchronized
    fun toState(): Map<String, String> = mapOf(
        "version" to "1",
        "state" to state.name,
        "manualPause" to manualPause.toString(),
        "clockMs" to (clockMs?.toString() ?: ""),
        "startedMs" to (startedMs?.toString() ?: ""),
        "movingTimeS" to movingTimeS.toString(),
        "distanceM" to distanceM.toString(),
        "maxKmh" to maxKmh.toString(),
        "elevationGainM" to elevation.gainMeters.toString(),
        "lowSinceMs" to (lowSinceMs?.toString() ?: ""),
        "highSinceMs" to (highSinceMs?.toString() ?: ""),
        "previous" to (previous?.let { "${it.timeMs},${it.lat},${it.lon},${it.accuracyM}" } ?: ""),
        "distanceOrigin" to (distanceOrigin?.let { "${it.timeMs},${it.lat},${it.lon},${it.accuracyM}" } ?: ""),
        "speeds" to speeds.joinToString(","),
        "altitudes" to elevation.samples.joinToString(","),
        "referencePressure" to (referencePressure?.toString() ?: ""),
        "elevationBase" to (elevation.baseline?.toString() ?: ""),
        "pressureSource" to pressureSource.toString(),
    )

    companion object {
        /** 欠損や非有限値を含む退避データは、部分的に復元せず全体を破棄する。 */
        fun fromState(map: Map<String, String>): RideSession? = runCatching {
            fun value(key: String) = requireNotNull(map[key])
            fun number(text: String) = text.toDouble().also { require(it.isFinite()) }
            fun nonnegative(key: String) = number(value(key)).also { require(it >= 0.0) }
            fun time(key: String) = value(key).let { text ->
                if (text.isEmpty()) null else text.toLong().also { require(it >= 0) }
            }
            fun optionalNumber(key: String) = value(key).let { if (it.isEmpty()) null else number(it) }
            fun series(key: String, limit: Int): List<Double> = value(key).let { text ->
                if (text.isEmpty()) emptyList() else text.split(',').map { number(it) }
            }.also { require(it.size <= limit) }
            require(value("version") == "1")
            RideSession().apply {
                state = RideState.valueOf(value("state"))
                manualPause = value("manualPause").toBooleanStrict()
                clockMs = time("clockMs")
                startedMs = time("startedMs")
                movingTimeS = nonnegative("movingTimeS")
                distanceM = nonnegative("distanceM")
                maxKmh = nonnegative("maxKmh")
                lowSinceMs = time("lowSinceMs")
                highSinceMs = time("highSinceMs")
                fun parseFix(text: String): Fix? {
                    if (text.isEmpty()) return null
                    val parts = text.split(',')
                    require(parts.size == 4)
                    return Fix(parts[0].toLong(), number(parts[1]), number(parts[2]), parts[3].toFloat()).also {
                        require(it.timeMs >= 0 && it.lat in -90.0..90.0 && it.lon in -180.0..180.0)
                        require(it.accuracyM.isFinite() && it.accuracyM >= 0f)
                        require(clockMs != null && it.timeMs <= clockMs!!)
                    }
                }
                previous = parseFix(value("previous"))
                distanceOrigin = parseFix(map["distanceOrigin"] ?: value("previous"))
                speeds.addAll(series("speeds", 3).also { values -> require(values.all { it in 0.0..(120.0 / 3.6) }) })
                elevation = ElevationGainAccumulator(
                    gainMeters = nonnegative("elevationGainM"),
                    baseline = optionalNumber("elevationBase"),
                    samples = series("altitudes", ElevationGainAccumulator.WINDOW_SIZE),
                )
                referencePressure = optionalNumber("referencePressure")
                pressureSource = value("pressureSource").toBooleanStrict()
                require(referencePressure == null || referencePressure!! > 0.0)
                require(!pressureSource || referencePressure != null)
                require(!manualPause || state == RideState.PAUSED)
                // A resumed ride can have a start time before its next fix sets the clock.
                require(startedMs == null || (clockMs?.let { startedMs!! <= it } ?: (state != RideState.IDLE)))
                require(listOfNotNull(lowSinceMs, highSinceMs).all { clockMs != null && it <= clockMs!! })
            }
        }.getOrNull()
    }
}
