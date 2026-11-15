package com.gorite.cyclemap.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RerouteState(
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val currentRouteNodeIds: List<Long> = emptyList(),
    val rerouteAttemptCount: Int = 0
)

class AdaptiveRerouteManager(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val rerouteDistanceThreshold: Double = 30.0, // plans.md: 30m
    private val consecutiveSamples: Int = 3,
    private val routerAction: suspend (startNodeId: Long, goalNodeId: Long) -> List<Long>?
) {
    // All mutable manager state, including the detector, is guarded by this lock.
    private val lock = Any()
    private var rerouteGeneration = 0L
    private val detector = OffRouteDetector(
        offRouteDistanceMeters = rerouteDistanceThreshold,
        requiredConsecutive = consecutiveSamples,
        recoverDistanceMeters = 15.0
    )

    private val _state = MutableStateFlow(RerouteState())
    val state: StateFlow<RerouteState> = _state.asStateFlow()

    private var activeRerouteJob: Job? = null
    val currentRerouteJob: Job? get() = synchronized(lock) { activeRerouteJob }

    private var lastRerouteStartNodeId: Long? = null

    fun setInitialRoute(nodeIds: List<Long>) {
        synchronized(lock) {
            resetLocked()
            _state.update { it.copy(currentRouteNodeIds = nodeIds) }
        }
    }

    fun reset() {
        synchronized(lock) { resetLocked() }
    }

    private fun resetLocked() {
        rerouteGeneration++
        activeRerouteJob?.cancel()
        detector.reset()
        lastRerouteStartNodeId = null
        _state.update {
            it.copy(isOffRoute = false, isRerouting = false)
        }
    }

    fun onLocationUpdate(
        distanceToRouteMeters: Double,
        currentSpeedMps: Float,
        currentNodeId: Long,
        goalNodeId: Long
    ) {
        // 速度に応じた可変閾値 (BUG防止: 高速走行時はGPSジッターを吸収)
        val dynamicThreshold = if (currentSpeedMps > 8.0f) {
            (currentSpeedMps * 2.0).toDouble().coerceIn(30.0, 60.0)
        } else {
            rerouteDistanceThreshold
        }

        val jobToStart = synchronized(lock) {
            val wasOffRoute = detector.isOffRoute
            // 既に逸脱中の復帰判定は固定復帰閾値 (15m) で行い、逸脱判定時のみ速度適応動的閾値 (30m~60m) を適用
            val effectiveDist = if (wasOffRoute) {
                distanceToRouteMeters
            } else {
                distanceToRouteMeters * (rerouteDistanceThreshold / dynamicThreshold)
            }
            val isOff = detector.update(effectiveDist)

            if (isOff) {
                _state.update { it.copy(isOffRoute = true) }
                if (!wasOffRoute || currentNodeId != lastRerouteStartNodeId) {
                    lastRerouteStartNodeId = currentNodeId
                    return@synchronized triggerReroute(currentNodeId, goalNodeId)
                }
            } else if (wasOffRoute) {
                lastRerouteStartNodeId = null
                _state.update { it.copy(isOffRoute = false) }
            }
            null
        }
        // Start outside the lock, even when the supplied scope runs undispatched.
        jobToStart?.start()
    }

    // Called with lock held. Register the lazy job before allowing it to run.
    private fun triggerReroute(startNodeId: Long, goalNodeId: Long): Job {
        val generation = ++rerouteGeneration
        // 直前の実行中ジョブはキャンセルし、最新の位置基準で再探索 (plans.md §6 P1-3)
        activeRerouteJob?.cancel()

        _state.update {
            it.copy(isRerouting = true, rerouteAttemptCount = it.rerouteAttemptCount + 1)
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // バックグラウンドで再探索。結果到着まで旧ルートを表示し続ける (旧ルートは消さない)
                val newRoute = routerAction(startNodeId, goalNodeId)
                synchronized(lock) {
                    if (generation != rerouteGeneration) return@synchronized
                    coroutineContext.ensureActive()
                    if (newRoute != null && newRoute.isNotEmpty()) {
                        detector.reset()
                        _state.update {
                            it.copy(
                                isOffRoute = false,
                                isRerouting = false,
                                currentRouteNodeIds = newRoute
                            )
                        }
                    } else {
                        _state.update { it.copy(isRerouting = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    if (generation == rerouteGeneration) {
                        _state.update { it.copy(isRerouting = false) }
                    }
                }
            }
        }
        activeRerouteJob = job
        return job
    }
}
