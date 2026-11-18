package com.gorite.cyclemap.routing

import kotlin.test.*

class BicycleDirectionTest {
    @Test fun explicitBicycleDirectionHasPriorityOverAllOtherTags() {
        for ((value, expected) in mapOf(
            "yes" to BicycleDirection.FORWARD_ONLY, "true" to BicycleDirection.FORWARD_ONLY, "1" to BicycleDirection.FORWARD_ONLY,
            "-1" to BicycleDirection.REVERSE_ONLY,
            "no" to BicycleDirection.BOTH, "false" to BicycleDirection.BOTH, "0" to BicycleDirection.BOTH,
        )) {
            for (oneway in listOf("yes", "-1", "no")) {
                val tags = mapOf("oneway:bicycle" to value, "oneway" to oneway, "junction" to "roundabout", "cycleway" to "opposite_lane")
                assertEquals(expected, resolveBicycleDirection(tags), "$tags")
                for (reverse in listOf(false, true)) {
                    val allowed = expected == BicycleDirection.BOTH || reverse == (expected == BicycleDirection.REVERSE_ONLY)
                    assertEquals(allowed, classifyBicycleRoad(tags, reverse).roadway == BikeAccess.ALLOWED, "$tags reverse=$reverse")
                }
            }
        }
    }

    @Test fun oppositeFacilitiesAndRoundaboutsRespectOverrides() {
        assertEquals(BicycleDirection.BOTH, resolveBicycleDirection(emptyMap()))
        assertEquals(BicycleDirection.FORWARD_ONLY, resolveBicycleDirection(mapOf("junction" to "roundabout")))
        for (value in listOf("no", "false", "0")) {
            assertEquals(BicycleDirection.BOTH, resolveBicycleDirection(mapOf("junction" to "roundabout", "oneway" to value)))
        }
        for (key in listOf("cycleway", "cycleway:both", "cycleway:left", "cycleway:right")) {
            for (value in listOf("opposite", "opposite_lane", "opposite_track")) {
                for (oneway in listOf("yes", "-1")) {
                    val tags = mapOf("oneway" to oneway, key to value)
                    assertEquals(BicycleDirection.BOTH, resolveBicycleDirection(tags), "$tags")
                    for (reverse in listOf(false, true)) {
                        val info = classifyBicycleRoad(tags, reverse)
                        assertEquals(BikeAccess.ALLOWED, info.roadway)
                        assertTrue(info.flags and BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED != 0)
                    }
                }
            }
        }
        assertEquals(BicycleDirection.FORWARD_ONLY, resolveBicycleDirection(mapOf("oneway" to "yes", "cycleway" to "lane")))
        assertEquals(BicycleDirection.FORWARD_ONLY, resolveBicycleDirection(mapOf(
            "oneway" to "yes", "cycleway" to "opposite_lane", "cycleway:bicycle" to "no",
        )))
    }
}
