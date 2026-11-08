package com.gorite.cyclemap.ui.mapview

import com.gorite.cyclemap.power.CameraMotionPolicy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 約1秒間隔でしか届かない GPS 位置を、描画フレームごとに連続した位置へ補間する。
 *
 * 新しい位置が届くたびに「いま表示している位置」から新位置へ、前回からの経過時間をかけて
 * 等速で滑らせる (マーカーが1秒ごとに跳ばない)。次の位置が遅れたときは、速度と進行方向で
 * 少しだけ先へ推測して動きを止めない。大きく離れたとき (初回・リルート・測位復帰) は滑らせずに置き換える。
 */
internal class LocationGlide(
    private val minGlideMs: Long = 300L,
    private val maxGlideMs: Long = 1_500L,
    private val snapDistanceMeters: Double = 60.0,
    private val maxExtrapolateMs: Long = 1_500L,
    private val minExtrapolateSpeedMps: Double = 1.0,
    private val leadMs: Long = 400L,
) {
    data class Position(val latitude: Double, val longitude: Double)

    private var hasPosition = false
    private var fromLat = 0.0
    private var fromLon = 0.0
    private var toLat = 0.0
    private var toLon = 0.0
    private var startMs = 0L
    private var durationMs = 1L
    private var speedMps = 0.0
    private var bearingDeg = Double.NaN
    private var lastFixMs = Long.MIN_VALUE

    fun reset() {
        hasPosition = false
        lastFixMs = Long.MIN_VALUE
    }

    /** 新しい位置を渡す。[speedMps]・[bearingDeg] は不明なら null か NaN。 */
    fun onFix(latitude: Double, longitude: Double, speedMps: Float?, bearingDeg: Float?, nowMs: Long) {
        val speed = speedMps?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val course = bearingDeg?.toDouble()?.takeIf { it.isFinite() } ?: Double.NaN
        val shown = position(nowMs)
        val interval = if (lastFixMs == Long.MIN_VALUE) maxGlideMs else nowMs - lastFixMs
        lastFixMs = nowMs

        // 少し先を狙う: 滑らせ終わった瞬間に次の位置が届くと、表示が常に1回分遅れるため
        var targetLat = latitude
        var targetLon = longitude
        if (speed >= minExtrapolateSpeedMps && course.isFinite()) {
            val moved = advance(latitude, longitude, speed * leadMs / 1000.0, course)
            targetLat = moved.latitude
            targetLon = moved.longitude
        }

        if (shown == null ||
            CameraMotionPolicy.distanceMeters(shown.latitude, shown.longitude, targetLat, targetLon) > snapDistanceMeters
        ) {
            fromLat = targetLat; fromLon = targetLon
            toLat = targetLat; toLon = targetLon
            durationMs = 1L
        } else {
            fromLat = shown.latitude; fromLon = shown.longitude
            toLat = targetLat; toLon = targetLon
            durationMs = interval.coerceIn(minGlideMs, maxGlideMs)
        }
        startMs = nowMs
        this.speedMps = speed
        this.bearingDeg = course
        hasPosition = true
    }

    /** [nowMs] に表示すべき位置。まだ位置が一度も届いていなければ null。 */
    fun position(nowMs: Long): Position? {
        if (!hasPosition) return null
        val t = (nowMs - startMs).toDouble() / durationMs
        if (t < 1.0) {
            val k = t.coerceAtLeast(0.0)
            return Position(fromLat + (toLat - fromLat) * k, fromLon + (toLon - fromLon) * k)
        }
        val extra = (nowMs - startMs - durationMs).coerceIn(0L, maxExtrapolateMs)
        if (speedMps >= minExtrapolateSpeedMps && bearingDeg.isFinite() && extra > 0L) {
            return advance(toLat, toLon, speedMps * extra / 1000.0, bearingDeg)
        }
        return Position(toLat, toLon)
    }

    private fun advance(lat: Double, lon: Double, meters: Double, bearingDeg: Double): Position {
        val rad = Math.toRadians(bearingDeg)
        val dLat = meters * cos(rad) / METERS_PER_DEGREE
        val dLon = meters * sin(rad) / (METERS_PER_DEGREE * cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        return Position(lat + dLat, lon + dLon)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}

/** 角度 (度) の計算。 */
internal object Angles {
    fun normalize(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    /** [from] から [to] への最短の回転量 (-180〜180)。 */
    fun shortestDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f + 360f) % 360f - 180f
}

/**
 * 目標の角度へ、時定数 [timeConstantSec] で指数的に近づける。
 * センサーが細かく目標を更新しても、アニメーションを作り直さず途切れない。
 */
internal class AngleFollower(
    private val timeConstantSec: Float = 0.12f,
    private val settleDegrees: Float = 0.15f,
) {
    var current: Float = Float.NaN
        private set

    fun snap(angle: Float) {
        current = Angles.normalize(angle)
    }

    fun step(target: Float, dtSec: Float): Float {
        val goal = Angles.normalize(target)
        if (current.isNaN()) {
            current = goal
            return current
        }
        val delta = Angles.shortestDelta(current, goal)
        current = if (abs(delta) < settleDegrees) {
            goal
        } else {
            Angles.normalize(current + delta * (1f - exp(-dtSec / timeConstantSec)))
        }
        return current
    }

    fun isSettled(target: Float): Boolean =
        current.isFinite() && abs(Angles.shortestDelta(current, Angles.normalize(target))) < settleDegrees
}

/**
 * ズーム値を目標へ指数的に近づける。+/-ボタンを続けて押すと目標が積み上がる。
 * 案内中は毎フレーム moveCamera でカメラを追従させているため、MapLibre の easeCamera のような
 * 「進行中アニメーション」は毎フレーム打ち切られる。ズームも同じ moveCamera の流れの中で進める。
 */
internal class ZoomFollower(
    private val timeConstantSec: Float = 0.09f,
    private val settle: Double = 0.01,
) {
    var current: Double = Double.NaN
        private set
    var target: Double = Double.NaN
        private set

    /** [actual] は地図の現在のズーム。進行中なら積み上げ、止まっていれば実際の値から始める。 */
    fun nudge(delta: Double, actual: Double, min: Double, max: Double, inProgress: Boolean) {
        val base = if (inProgress && target.isFinite()) target else actual
        current = if (inProgress && current.isFinite()) current else actual
        target = (base + delta).coerceIn(min, max)
    }

    fun step(dtSec: Float): Double {
        if (!current.isFinite() || !target.isFinite()) return current
        val diff = target - current
        current = if (kotlin.math.abs(diff) < settle) target else current + diff * (1.0 - exp(-dtSec / timeConstantSec))
        return current
    }

    val isSettled: Boolean get() = !current.isFinite() || !target.isFinite() || kotlin.math.abs(target - current) < settle
}
