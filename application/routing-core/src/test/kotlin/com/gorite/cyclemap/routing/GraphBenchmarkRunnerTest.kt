package com.gorite.cyclemap.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphBenchmarkRunnerTest {
    private fun sample(reachable: Boolean, ms: Double, dist: Double) =
        BenchSample(reachable, false, ms, 10, dist, dist)

    @Test
    fun `中央値と到達率は到達した組だけで集計する`() {
        val s = BenchVariantSummary(
            BenchVariant("x"),
            listOf(sample(true, 10.0, 100.0), sample(true, 30.0, 300.0), sample(true, 20.0, 200.0), sample(false, 999.0, 0.0)),
        )
        assertEquals(0.75, s.reachableRate, 1e-9)
        assertEquals(20.0, s.median { it.elapsedMillis }, 1e-9)
    }

    @Test
    fun `基準比の距離増加は両方到達した組だけで計算する`() {
        val base = BenchVariantSummary(BenchVariant("基準"), listOf(sample(true, 1.0, 100.0), sample(true, 1.0, 200.0), sample(false, 1.0, 0.0)))
        val other = BenchVariantSummary(BenchVariant("案"), listOf(sample(true, 1.0, 110.0), sample(true, 1.0, 220.0), sample(true, 1.0, 50.0)))
        val report = formatBenchReport("g", 3, listOf(base, other))
        assertTrue(report.contains("| 案 |"))
        assertTrue(report.contains("+10.00%"), report)
    }

    @Test
    fun `既定の探索フラグは現行のまま`() {
        val v = BenchVariant("既定")
        assertEquals(RoutePreference.RECOMMENDED, v.preference)
        assertEquals(1.0, v.arterialPenaltyMultiplier, 0.0)
    }
}
