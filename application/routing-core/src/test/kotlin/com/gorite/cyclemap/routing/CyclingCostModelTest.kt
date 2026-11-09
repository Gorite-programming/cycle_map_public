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
}
