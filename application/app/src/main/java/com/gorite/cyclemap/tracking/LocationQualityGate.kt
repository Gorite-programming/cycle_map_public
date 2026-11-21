package com.gorite.cyclemap.tracking

import com.gorite.cyclemap.utils.haversineMeters

/** 品質判定にかける 1 つの測位。Android の Location に依存せず、JVM のテストで動かせるようにする。 */
internal data class GateFix(
    val latitude: Double,
    val longitude: Double,
    val elapsedNanos: Long,
    val accuracyMeters: Float? = null,
    /** 測位の速度 (ドップラー由来で、位置の飛びに強い)。無ければ null。 */
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
)

internal enum class GateVerdict {
    /** 採用して、位置を更新する。 */
    ACCEPT,

    /** ドップラーが「止まっている」と言っている。位置が動いて見えても、最後に採用した位置に留める (軌跡・距離に足さない)。 */
    STATIONARY,

    /** 精度が悪すぎる。 */
    REJECT_INACCURATE,

    /** 位置が不自然に飛んだ (速度の上限超え、またはドップラーの速度と食い違う)。 */
    REJECT_JUMP,
}

/**
 * 測位の品質ゲート。実走の記録 (2026-11-21、Amazfit Bip 6 と比較) で、次のことが分かった。
 *  - 停止中 (約 60 秒) に、位置が 60〜70 m の幅で往復し、距離に約 460 m が加算された (記録の距離の水増しの約 7 割)。
 *  - 走行中にも、1 歩で数十 m の飛びがあり、速度が 35〜48 km/h に見えた (実際の最大は 21.7 km/h)。
 * そこで、位置そのものではなく、ドップラーの速度を手がかりにして、停止中の位置を動かさず、食い違った飛びを捨てる。
 *
 * 状態は「最後に採用した位置」だけ。ドップラー速度が無い測位では、速度の上限と精度の判定だけを行う。
 */
internal class LocationQualityGate(
    private val maxAcceptableAccuracyMeters: Float = 100f,
    private val stopSpeedMps: Float = STOP_SPEED_MPS,
    private val maxCyclingSpeedMps: Double = MAX_CYCLING_SPEED_MPS,
    private val reanchorAfterRejects: Int = REANCHOR_AFTER_REJECTS,
) {
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var anchorNanos = 0L
    private var consecutiveRejects = 0

    fun reset() {
        anchorLat = Double.NaN
        anchorLon = Double.NaN
        anchorNanos = 0L
        consecutiveRejects = 0
    }

    fun evaluate(fix: GateFix): GateVerdict {
        if (fix.accuracyMeters != null && fix.accuracyMeters > maxAcceptableAccuracyMeters) {
            return GateVerdict.REJECT_INACCURATE
        }
        if (anchorLat.isNaN()) return accept(fix)

        val dtSeconds = (fix.elapsedNanos - anchorNanos) / 1_000_000_000.0
        if (dtSeconds <= 0.0) return GateVerdict.REJECT_JUMP
        val distanceMeters = haversineMeters(anchorLat, anchorLon, fix.latitude, fix.longitude)
        val impliedMps = distanceMeters / dtSeconds

        val doppler = fix.speedMps?.takeIf { it.isFinite() && it >= 0f && (fix.speedAccuracyMps == null || fix.speedAccuracyMps <= MAX_TRUSTED_SPEED_ACCURACY_MPS) }
        if (doppler != null && doppler < stopSpeedMps) {
            // 本当に動いているのにドップラーが 0 を返し続ける場合に固まらないよう、遠く離れたら位置を信じる。
            if (distanceMeters > STATIONARY_REANCHOR_METERS) return accept(fix)
            anchorNanos = fix.elapsedNanos
            consecutiveRejects = 0
            return GateVerdict.STATIONARY
        }

        val isJump = if (doppler != null) {
            impliedMps > doppler * 2.0 + DOPPLER_MISMATCH_MARGIN_MPS || (impliedMps > maxCyclingSpeedMps && dtSeconds <= MAX_JUMP_WINDOW_SECONDS)
        } else {
            impliedMps > maxCyclingSpeedMps && dtSeconds <= MAX_JUMP_WINDOW_SECONDS
        }
        if (!isJump) return accept(fix)

        consecutiveRejects++
        // 飛んだ先が、本当の位置だった場合 (トンネルの出口、長い途切れの後) に、いつまでも捨て続けない。
        return if (consecutiveRejects >= reanchorAfterRejects) accept(fix) else GateVerdict.REJECT_JUMP
    }

    private fun accept(fix: GateFix): GateVerdict {
        anchorLat = fix.latitude
        anchorLon = fix.longitude
        anchorNanos = fix.elapsedNanos
        consecutiveRejects = 0
        return GateVerdict.ACCEPT
    }

    companion object {
        /** これ未満のドップラー速度は「止まっている」(自動一時停止の 2 km/h と同じ範囲)。 */
        const val STOP_SPEED_MPS = 0.5f

        /** 自転車の速度の上限 (約 65 km/h)。長い下り坂まで含めた余裕を見ている。 */
        const val MAX_CYCLING_SPEED_MPS = 18.0

        /** ドップラー速度の 2 倍にこの値を足したものを超える移動は、ドップラーと食い違う飛びとみなす。 */
        const val DOPPLER_MISMATCH_MARGIN_MPS = 5.0

        /** 速度の精度がこれより悪いドップラーは信用しない。 */
        const val MAX_TRUSTED_SPEED_ACCURACY_MPS = 2.0f

        /** 上限超えの判定は、この秒数以内の間隔にだけ使う (長い途切れの後の再取得は、飛びではない)。 */
        const val MAX_JUMP_WINDOW_SECONDS = 10.0

        /** 連続でこの回数、飛びと判定された測位は、新しい位置として採用し直す。 */
        const val REANCHOR_AFTER_REJECTS = 3

        /** 停止の判定中でも、採用済みの位置からこの距離を超えたら、動いていると見て位置を採用し直す。 */
        const val STATIONARY_REANCHOR_METERS = 100.0
    }
}
