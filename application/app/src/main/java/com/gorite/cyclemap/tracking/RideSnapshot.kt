package com.gorite.cyclemap.tracking

/** サイコンモードの走行状態。IDLE = まだ走り出していない、PAUSED = 止まっている (手動または自動)。 */
enum class RideState { IDLE, RUNNING, PAUSED }

/** [RideSession] のある時点の値。単位は速度 km/h、距離 m、時間 秒、標高 m。 */
data class RideSnapshot(
    val state: RideState = RideState.IDLE,
    val speedKmh: Double = 0.0,
    val distanceM: Double = 0.0,
    /** 動いていた時間 (一時停止中は進まない)。 */
    val movingTimeS: Double = 0.0,
    /** 走り出してからの経過時間 (一時停止中も進む)。 */
    val elapsedS: Double = 0.0,
    val averageKmh: Double = 0.0,
    val maxKmh: Double = 0.0,
    val elevationGainM: Double = 0.0,
)
