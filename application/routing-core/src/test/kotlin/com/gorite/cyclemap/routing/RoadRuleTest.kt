package com.gorite.cyclemap.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoadRuleTest {
    private fun kind(tags: Map<String, String>, wrongWay: Boolean = false, against: Boolean = false) =
        roadRuleKindOf(classifyBicycleRoad(tags, against), wrongWay)

    @Test
    fun ordinaryRoadsAreNotWorthMentioning() {
        assertNull(kind(mapOf("highway" to "primary")))
        assertNull(kind(mapOf("highway" to "trunk", "sidewalk" to "both")))
        assertNull(kind(mapOf("highway" to "residential", "oneway" to "yes")))
    }

    @Test
    fun motorOnlyRoadIsAWarning() {
        assertEquals(RoadRuleKind.MOTOR_ONLY, kind(mapOf("highway" to "trunk", "motorroad" to "yes")))
        assertEquals(RoadRuleKind.MOTOR_ONLY, kind(mapOf("highway" to "motorway")))
        assertEquals(true, RoadRuleKind.MOTOR_ONLY.isWarning)
    }

    @Test
    fun wrongWayOnlyCountsOnOneWayEdgesForBicycles() {
        assertEquals(RoadRuleKind.WRONG_WAY, kind(mapOf("highway" to "residential", "oneway" to "yes"), wrongWay = true))
        assertNull(kind(mapOf("highway" to "residential", "oneway" to "yes"), wrongWay = false))
        // 自転車だけ逆向きも通れる (oneway:bicycle=no) 道では、逆向きでも逆走にせず、「逆向きも可」と知らせる。両方向の道では何も言わない
        assertEquals(RoadRuleKind.CONTRAFLOW_ALLOWED, kind(mapOf("highway" to "residential", "oneway" to "yes", "oneway:bicycle" to "no"), wrongWay = true))
        assertNull(kind(mapOf("highway" to "residential"), wrongWay = true))
    }

    @Test
    fun otherForbiddenAndDismountAndSharedAndFacility() {
        assertEquals(RoadRuleKind.BICYCLE_FORBIDDEN, kind(mapOf("highway" to "primary", "bicycle" to "no")))
        assertEquals(RoadRuleKind.DISMOUNT, kind(mapOf("highway" to "footway", "bicycle" to "dismount")))
        assertEquals(RoadRuleKind.PEDESTRIAN_SHARED, kind(mapOf("highway" to "footway", "bicycle" to "yes")))
        assertEquals(RoadRuleKind.BIKE_FACILITY, kind(mapOf("highway" to "secondary", "cycleway" to "lane")))
        assertEquals(RoadRuleKind.BIKE_FACILITY, kind(mapOf("highway" to "trunk", "cycleway" to "shared_lane", "sidewalk" to "both")))
        assertEquals(false, RoadRuleKind.BIKE_FACILITY.isWarning)
    }
}
