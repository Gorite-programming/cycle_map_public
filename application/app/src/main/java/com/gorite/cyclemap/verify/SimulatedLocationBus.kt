package com.gorite.cyclemap.verify

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 検証用: 実機で、走らずに案内・リルートを試すための模擬位置の経路。
 * 発行側は debug ビルド専用の `LocationDebugReceiver` (src/debug) だけで、リリースには含まれない。
 * 購読側は `ServiceLocationUpdates`。模擬位置を受けてから [HOLD_MS] の間は、本物の GPS の更新を無視する
 * (止まっている端末の本物の位置と、模擬の走行が交互に入って案内が乱れるのを防ぐ)。
 */
object SimulatedLocationBus {
    private const val HOLD_MS = 15_000L
    private val _locations = MutableSharedFlow<Location>(extraBufferCapacity = 16)
    val locations: SharedFlow<Location> = _locations

    @Volatile
    private var lastPostElapsedMs = Long.MIN_VALUE / 2

    /** 模擬位置を流している最中か (最後の模擬位置から [HOLD_MS] 以内)。 */
    fun isActive(): Boolean = SystemClock.elapsedRealtime() - lastPostElapsedMs < HOLD_MS

    fun post(location: Location): Boolean {
        lastPostElapsedMs = SystemClock.elapsedRealtime()
        return _locations.tryEmit(location)
    }
}
