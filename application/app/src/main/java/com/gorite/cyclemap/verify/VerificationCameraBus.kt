package com.gorite.cyclemap.verify

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 検証用: 地図カメラを任意の地点・ズームへ動かす要求 (緯度経度は度、[tilt]・[bearing] は度)。 */
data class VerificationCameraRequest(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val tilt: Double = 0.0,
    val bearing: Double = 0.0,
)

/**
 * 検証用: 実機のスクリーンショット比較などで、現在地に依存せず同じ地点・ズームを再現するための経路。
 * 発行側は debug ビルド専用の `CameraDebugReceiver` (src/debug) のみで、リリースには含まれない。
 * 購読側は `VectorMapView`。購読者がいなければ要求は捨てられる。
 */
object VerificationCameraBus {
    private val _requests = MutableSharedFlow<VerificationCameraRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<VerificationCameraRequest> = _requests

    fun post(request: VerificationCameraRequest): Boolean = _requests.tryEmit(request)
}
