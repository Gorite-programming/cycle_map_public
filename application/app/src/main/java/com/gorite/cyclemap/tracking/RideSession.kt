package com.gorite.cyclemap.tracking

import com.gorite.cyclemap.routing.haversineMeters
import kotlin.math.pow

/** これを超える見かけの速さ (m/s、約 180 km/h) は位置の飛びとして扱う。 */
private const val MAX_PLAUSIBLE_MPS = 50.0

/** Android に依存しない走行統計。時刻は位置更新または表示更新から受け取る。 */
class RideSession {
    private var state = RideState.IDLE
    private var manualPause = false
    private var clockMs: Long? = null
    private var startedMs: Long? = null
    private var movingTimeS = 0.0
    private var distanceM = 0.0
    private var maxKmh = 0.0
    private var elevationGainM = 0.0
    private var lowSinceMs: Long? = null
    private var highSinceMs: Long? = null
    private var previous: Fix? = null
    private val speeds = ArrayDeque<Double>()
    private val altitudes = ArrayDeque<Double>()
    private var referencePressure: Double? = null
    private var elevationBase: Double? = null
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
        val old = previous
        // 遅延・重複した位置で距離や平滑化の履歴を巻き戻さない。
        if (old != null && timeMs <= old.timeMs) return
        if (clockMs != null && timeMs < clockMs!!) return
        val dtMs = old?.let { timeMs - it.timeMs }
        val stepM = old?.let { haversineMeters(it.lat, it.lon, lat, lon) }
        // 位置の飛び (GPS の誤差・別の測位源への切り替え): 時間の割に離れすぎた点は、距離にも速度にも使わない。
        // 本当に移動していたなら、30 秒以上たてば次の点が起点になる (距離は足さない)。
        if (old != null && dtMs != null && dtMs in 1..30_000 && stepM != null && stepM > MAX_PLAUSIBLE_MPS * dtMs / 1000.0 + 30.0) {
            advance(timeMs)
            return
        }
        val rawSpeed = speedMps?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: if (dtMs != null && dtMs in 1..30_000 && stepM != null) stepM / (dtMs / 1000.0) else 0.0
        advance(timeMs)
        if (dtMs != null && dtMs > 30_000) {
            speeds.clear()
            lowSinceMs = null
            highSinceMs = null
        }
        // 飛びは平均にも混ぜず、その位置を次の距離計算の起点にもしない。
        if (!rawSpeed.isFinite() || rawSpeed * 3.6 > 120.0) {
            previous = null
            lowSinceMs = null
            highSinceMs = null
            return
        }
        speeds.addLast(rawSpeed)
        if (speeds.size > 3) speeds.removeFirst()
        val speed = speeds.average()
        val wasPaused = state == RideState.PAUSED
        updateAutoPause(timeMs, speed * 3.6)
        if (!wasPaused && state == RideState.RUNNING && old != null &&
            accuracyM <= 20f && old.accuracyM <= 20f && speed >= 0.5 &&
            dtMs != null && dtMs in 1..30_000 && stepM != null && stepM.isFinite()
        ) distanceM += stepM
        if (state == RideState.RUNNING) maxKmh = maxOf(maxKmh, speed * 3.6)
        updateElevation(altitudeM, pressureHpa)
        previous = Fix(timeMs, lat, lon, accuracyM)
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
        } else {
            if (altitudeM == null || !altitudeM.isFinite()) return
            // 平均の加算がオーバーフローしないよう、先に点数で割る。
            altitudes.addLast(altitudeM)
            if (altitudes.size > 5) altitudes.removeFirst()
            altitudes.sumOf { it / altitudes.size }
        }
        if (!height.isFinite()) return
        // 気圧高度と絶対高度を切り替えた差は登りに数えない。
        if (elevationBase == null || pressureSource != usePressure || state != RideState.RUNNING) {
            elevationBase = height
        } else {
            val delta = height - elevationBase!!
            if (delta >= 3.0) {
                val total = elevationGainM + delta
                if (total.isFinite()) elevationGainM = total
                elevationBase = height
            } else if (delta < 0.0) elevationBase = height
        }
        pressureSource = usePressure
    }

    // 引数のない手動操作は、最後に呼び出し側から受け取った時刻を使う。
    private fun advance(timeMs: Long) {
        if (timeMs < 0 || (clockMs != null && timeMs < clockMs!!)) return
        if (state == RideState.RUNNING) {
            if (startedMs == null) startedMs = timeMs
            clockMs?.let { movingTimeS += (timeMs - it) / 1000.0 }
        }
        clockMs = timeMs
    }

    @Synchronized
    fun snapshot(nowMs: Long): RideSnapshot {
        advance(nowMs)
        return RideSnapshot(
            state = state,
            speedKmh = if (state == RideState.RUNNING && speeds.isNotEmpty()) speeds.average() * 3.6 else 0.0,
            distanceM = distanceM,
            movingTimeS = movingTimeS,
            elapsedS = startedMs?.let { ((clockMs ?: it) - it).coerceAtLeast(0) / 1000.0 } ?: 0.0,
            averageKmh = if (movingTimeS > 0.0) distanceM / movingTimeS * 3.6 else 0.0,
            maxKmh = maxKmh,
            elevationGainM = elevationGainM,
        )
    }

    @Synchronized
    fun start() {
        if (state == RideState.RUNNING) return
        state = RideState.RUNNING
        manualPause = false
        if (startedMs == null) startedMs = clockMs
        lowSinceMs = null
        highSinceMs = null
        previous = null
        speeds.clear()
    }

    @Synchronized
    fun pause() {
        state = RideState.PAUSED
        manualPause = true
        lowSinceMs = null
        highSinceMs = null
        previous = null
        speeds.clear()
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
        elevationGainM = 0.0
        lowSinceMs = null
        highSinceMs = null
        previous = null
        speeds.clear()
        altitudes.clear()
        referencePressure = null
        elevationBase = null
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
        "elevationGainM" to elevationGainM.toString(),
        "lowSinceMs" to (lowSinceMs?.toString() ?: ""),
        "highSinceMs" to (highSinceMs?.toString() ?: ""),
        "previous" to (previous?.let { "${it.timeMs},${it.lat},${it.lon},${it.accuracyM}" } ?: ""),
        "speeds" to speeds.joinToString(","),
        "altitudes" to altitudes.joinToString(","),
        "referencePressure" to (referencePressure?.toString() ?: ""),
        "elevationBase" to (elevationBase?.toString() ?: ""),
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
                elevationGainM = nonnegative("elevationGainM")
                lowSinceMs = time("lowSinceMs")
                highSinceMs = time("highSinceMs")
                previous = value("previous").let { text ->
                    if (text.isEmpty()) null else {
                        val parts = text.split(',')
                        require(parts.size == 4)
                        Fix(parts[0].toLong(), number(parts[1]), number(parts[2]), parts[3].toFloat()).also {
                            require(it.timeMs >= 0 && it.lat in -90.0..90.0 && it.lon in -180.0..180.0)
                            require(it.accuracyM.isFinite() && it.accuracyM >= 0f)
                            require(clockMs != null && it.timeMs <= clockMs!!)
                        }
                    }
                }
                speeds.addAll(series("speeds", 3).also { values -> require(values.all { it in 0.0..(120.0 / 3.6) }) })
                altitudes.addAll(series("altitudes", 5))
                referencePressure = optionalNumber("referencePressure")
                elevationBase = optionalNumber("elevationBase")
                pressureSource = value("pressureSource").toBooleanStrict()
                require(referencePressure == null || referencePressure!! > 0.0)
                require(!pressureSource || referencePressure != null)
                require(!manualPause || state == RideState.PAUSED)
                require(startedMs == null || (clockMs != null && startedMs!! <= clockMs!!))
                require(listOfNotNull(lowSinceMs, highSinceMs).all { clockMs != null && it <= clockMs!! })
            }
        }.getOrNull()
    }
}
