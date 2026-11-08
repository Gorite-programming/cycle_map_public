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
 * 地図カメラを任意の地点・ズームへ動かす要求の経路。
 * 発行側: debug ビルド専用の `CameraDebugReceiver` (src/debug、実機の検証用) と、
 * 検索結果を選んだときにその場所へ地図を動かす `MapScreenScaffold`。
 * 購読側は `VectorMapView`。購読者がいなければ要求は捨てられる。
 */
object VerificationCameraBus {
    private val _requests = MutableSharedFlow<VerificationCameraRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<VerificationCameraRequest> = _requests

    fun post(request: VerificationCameraRequest): Boolean = _requests.tryEmit(request)
}
