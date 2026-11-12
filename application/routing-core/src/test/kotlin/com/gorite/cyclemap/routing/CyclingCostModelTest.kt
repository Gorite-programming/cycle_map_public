package com.gorite.cyclemap.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CyclingCostModelTest {
    @Test
    fun excludesMotorways() {
        for (roadType in listOf("motorway", "motorway_link")) {
            val cost = CyclingCostModel.cost(GraphEdge(1, 2, 100.0, roadType))

            assertNull(cost, "Expected $roadType to be excluded")
        }
    }

    @Test
    fun everySupportedAndUnknownRoadTypeRespectsMinimumMultiplierForAllGrades() {
        val roadTypes = listOf(
            "primary",
            "secondary",
            "tertiary",
            "residential",
            "unclassified",
            "service",
            "track",
            "path",
            "foo",
        )
        val grades = listOf<Double?>(-30.0, -10.0, -4.0, -1.0, 0.0, 1.0, 5.0, 15.0, null)
        val distanceMeters = 123.45
        val minimumCost = distanceMeters * CyclingCostModel.minimumCostMultiplier()

        for (roadType in roadTypes) {
            for (gradePercent in grades) {
                val edge = GraphEdge(1, 2, distanceMeters, roadType, gradePercent = gradePercent)
                val cost = assertNotNull(
                    CyclingCostModel.cost(edge),
                    "Expected a cost for roadType=$roadType, gradePercent=$gradePercent",
                )

                assertTrue(
                    cost >= minimumCost - 1e-9,
                    "Expected cost $cost >= $minimumCost for roadType=$roadType, gradePercent=$gradePercent",
                )
            }
        }
    }

    @Test
    fun minimumCostMultiplierIsNinetyPercent() {
        assertEquals(0.90, CyclingCostModel.minimumCostMultiplier())
    }

    @Test
    fun sharedBicycleCostTableKeepsItsValues() {
        // CyclingCostModel と LazyMappedRoadGraph が共有する表 (BicycleCost)。値が変わると CH の作り直しが要るので固定する。
        val expected = mapOf(
            "primary" to 0.90, "secondary" to 0.95, "tertiary" to 1.00, "residential" to 1.20,
            "unclassified" to 1.25, "service" to 1.35, "track" to 1.60, "path" to 1.80, "foo" to 1.15,
        )
        for ((roadType, multiplier) in expected) {
            assertEquals(multiplier, BicycleCost.roadMultiplier(roadType), "roadType=$roadType")
            // 平坦な道では CyclingCostModel のコスト = 距離 × 表の係数
            assertEquals(100.0 * multiplier, CyclingCostModel.cost(GraphEdge(1, 2, 100.0, roadType, gradePercent = 0.0))!!, 1e-9)
        }
        assertNull(BicycleCost.roadMultiplier("motorway"))
        assertEquals(BicycleCost.MIN_MULTIPLIER, CyclingCostModel.minimumCostMultiplier())
    }

    @Test
    fun recommendedGradePenaltyIsContinuousAtItsBreakpoints() {
        val eps = 1e-9
        for (breakpoint in listOf(0.0, -4.0)) {
            val left = BicycleCost.recommendedGradePenalty(breakpoint - eps)
            val right = BicycleCost.recommendedGradePenalty(breakpoint + eps)
            assertTrue(kotlin.math.abs(left - right) < 1e-6, "grade=$breakpoint left=$left right=$right")
        }
        assertEquals(0.95, BicycleCost.recommendedGradePenalty(-4.0), 1e-12)
        assertEquals(0.95 + 6.0 * 0.03, BicycleCost.recommendedGradePenalty(-10.0), 1e-12)
    }
}
