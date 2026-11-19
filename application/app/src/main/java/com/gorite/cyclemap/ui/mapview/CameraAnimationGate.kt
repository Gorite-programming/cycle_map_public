package com.gorite.cyclemap.ui.mapview

import android.os.SystemClock
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.maps.MapLibreMap

/**
 * ナビ開始・現在地ボタンなどのカメラのアニメーション中に、現在地追従の毎フレームの moveCamera を止めるための門。
 *
 * MapLibre の moveCamera は、進行中の animateCamera / easeCamera を打ち切る。追従は毎フレーム moveCamera するので、
 * 何もしないと案内開始のズーム (NAVIGATION_ZOOM) などが 1 フレームで止まり、目的のズームに届かない (Antigravity の調査 APP-R1-02)。
 */
internal object CameraAnimationGate {
    /** アニメーションの終わりの通知が来ないときも、この時間を過ぎたら追従に戻す (余裕を見て少し長めに)。 */
    private const val MARGIN_MS = 150L

    @Volatile
    private var busyUntilMs = 0L

    fun isBusy(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = nowMs < busyUntilMs

    fun hold(durationMs: Long, nowMs: Long = SystemClock.elapsedRealtime()) {
        busyUntilMs = maxOf(busyUntilMs, nowMs + durationMs + MARGIN_MS)
    }

    fun release() {
        busyUntilMs = 0L
    }
}

/** 既定のカメラのアニメーション時間 (ミリ秒)。 */
internal const val EXCLUSIVE_CAMERA_ANIMATION_MS = 600

/**
 * 追従の moveCamera に打ち切られないカメラのアニメーション。終わる (または利用者の操作で打ち切られる) まで追従を止める。
 */
internal fun MapLibreMap.animateCameraExclusive(update: CameraUpdate, durationMs: Int = EXCLUSIVE_CAMERA_ANIMATION_MS) {
    CameraAnimationGate.hold(durationMs.toLong())
    animateCamera(update, durationMs, object : MapLibreMap.CancelableCallback {
        override fun onCancel() = CameraAnimationGate.release()
        override fun onFinish() = CameraAnimationGate.release()
    })
}
