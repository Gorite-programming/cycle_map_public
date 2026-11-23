package com.gorite.cyclemap.tracking

/** Applies the same ascent and descent thresholds to live and recorded elevations. */
internal class ElevationGainAccumulator(
    gainMeters: Double = 0.0,
    baseline: Double? = null,
    samples: List<Double> = emptyList(),
) {
    private val window = ArrayDeque(samples)
    val samples: List<Double> get() = window.toList()
    var gainMeters = gainMeters
        private set
    var baseline = baseline
        private set

    fun add(heightMeters: Double, smooth: Boolean = true) {
        if (!heightMeters.isFinite()) return
        val height = if (smooth) {
            window.addLast(heightMeters)
            if (window.size > WINDOW_SIZE) window.removeFirst()
            // Divide before summing to avoid overflow from large input values.
            window.sumOf { it / window.size }
        } else heightMeters
        if (!height.isFinite()) return

        val reference = baseline
        if (reference == null) {
            baseline = height
            return
        }
        val delta = height - reference
        if (delta >= THRESHOLD_METERS) {
            val total = gainMeters + delta
            if (total.isFinite()) gainMeters = total
            baseline = height
        } else if (delta <= -THRESHOLD_METERS) {
            baseline = height
        }
    }

    fun resetBaseline() {
        window.clear()
        baseline = null
    }

    companion object {
        const val WINDOW_SIZE = 5
        private const val THRESHOLD_METERS = 3.0
    }
}
