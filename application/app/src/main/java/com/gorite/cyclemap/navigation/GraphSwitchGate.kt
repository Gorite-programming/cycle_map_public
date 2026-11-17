package com.gorite.cyclemap.navigation

/** 現在地による先読みだけを抑制し、明示的な経路要求の切替には適用しない。 */
internal class GraphSwitchGate {
    private var candidate: String? = null
    private var count = 0
    private var sinceMs = 0L
    private var lastFixMs = Long.MIN_VALUE

    fun update(target: String?, current: String?, accuracyMeters: Double, fixMs: Long, busy: Boolean): Boolean {
        if (busy || target == null || target == current || !accuracyMeters.isFinite() || accuracyMeters !in 0.0..MAX_ACCURACY_METERS) {
            candidate = null
            count = 0
            return false
        }
        if (fixMs <= lastFixMs) return false
        lastFixMs = fixMs
        if (candidate != target) {
            candidate = target
            sinceMs = fixMs
            count = 0
        }
        count++
        return count >= REQUIRED_FIXES || fixMs - sinceMs >= STABLE_DURATION_MS
    }

    companion object {
        // 県境での測位の揺れによる連続ロードを避ける。
        private const val REQUIRED_FIXES = 3
        private const val STABLE_DURATION_MS = 30_000L
        private const val MAX_ACCURACY_METERS = 50.0
    }
}
