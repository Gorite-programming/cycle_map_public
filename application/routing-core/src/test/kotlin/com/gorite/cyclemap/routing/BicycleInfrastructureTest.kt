package com.gorite.cyclemap.routing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BicycleInfrastructureTest {
    private data class Case(
        val tags: Map<String, String>,
        val expected: BicycleRoadInfo,
        val reverse: Boolean = false,
    )

    private fun info(
        roadway: BikeAccess = BikeAccess.ALLOWED,
        left: SideInfo = SideInfo(SideFacility.NONE, roadway),
        right: SideInfo = SideInfo(SideFacility.NONE, roadway),
        flags: Int = 0,
    ) = BicycleRoadInfo(roadway, left, right, flags)

    private fun check(cases: List<Case>) {
        for (case in cases) {
            assertEquals(case.expected, classifyBicycleRoad(case.tags, case.reverse), "tags=${case.tags}, reverse=${case.reverse}")
        }
    }

    @Test
    fun roadwayDefaultsAndUnknownTags() {
        val cases = mutableListOf(
            Case(emptyMap(), info()),
            Case(mapOf("unknown" to "unknown"), info()),
            Case(mapOf("highway" to "unknown"), info(BikeAccess.UNKNOWN)),
        )
        for (highway in listOf(
            "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link",
            "residential", "service", "unclassified", "living_street", "road", "cycleway",
        )) cases += Case(mapOf("highway" to highway), info())
        for (highway in listOf("path", "track", "bridleway")) {
            cases += Case(mapOf("highway" to highway), info(BikeAccess.UNKNOWN))
            cases += Case(mapOf("highway" to highway, "bicycle" to "yes"), info())
        }
        check(cases.flatMap { listOf(it, it.copy(reverse = true)) })
    }

    @Test
    fun explicitAccessValuesAndPriority() {
        val values = mapOf(
            "yes" to BikeAccess.ALLOWED, "designated" to BikeAccess.ALLOWED, "permissive" to BikeAccess.ALLOWED,
            "no" to BikeAccess.FORBIDDEN, "dismount" to BikeAccess.DISMOUNT,
            "private" to BikeAccess.CONDITIONAL, "destination" to BikeAccess.CONDITIONAL,
            "customers" to BikeAccess.CONDITIONAL, "delivery" to BikeAccess.CONDITIONAL, "unknown" to BikeAccess.UNKNOWN,
        )
        val cases = mutableListOf<Case>()
        for ((value, access) in values) {
            for (key in listOf("bicycle", "vehicle", "access")) cases += Case(mapOf(key to value), info(access))
        }
        cases += listOf(
            Case(mapOf("bicycle" to "yes", "vehicle" to "no", "access" to "no"), info()),
            Case(mapOf("bicycle" to "no", "vehicle" to "yes", "access" to "yes"), info(BikeAccess.FORBIDDEN)),
            Case(mapOf("vehicle" to "yes", "access" to "no"), info()),
            Case(mapOf("vehicle" to "no", "access" to "yes"), info(BikeAccess.FORBIDDEN)),
            Case(mapOf("bicycle" to "unknown", "vehicle" to "yes"), info(BikeAccess.UNKNOWN)),
            Case(mapOf("vehicle" to "unknown", "access" to "yes"), info(BikeAccess.UNKNOWN)),
            Case(mapOf("bicycle" to "use_sidepath"), info(BikeAccess.FORBIDDEN, flags = BicycleRoadInfo.FLAG_USE_SIDEPATH)),
        )
        check(cases)
    }

    @Test
    fun restrictedHighwaysAndExplicitBicyclePermissions() {
        val cases = mutableListOf<Case>()
        for (highway in listOf("motorway", "motorway_link", "footway", "pedestrian", "corridor", "steps")) {
            val flags = if (highway in listOf("motorway", "motorway_link")) BicycleRoadInfo.FLAG_MOTORROAD else 0
            cases += Case(mapOf("highway" to highway), info(BikeAccess.FORBIDDEN, flags = flags))
            cases += Case(mapOf("highway" to highway, "vehicle" to "yes", "access" to "yes"), info(BikeAccess.FORBIDDEN, flags = flags))
            for (permission in listOf("yes", "designated", "permissive")) {
                cases += Case(mapOf("highway" to highway, "bicycle" to permission),
                    info(if (highway == "steps") BikeAccess.FORBIDDEN else BikeAccess.ALLOWED, flags = flags))
            }
        }
        cases += listOf(
            Case(mapOf("motorroad" to "yes"), info(BikeAccess.FORBIDDEN, flags = BicycleRoadInfo.FLAG_MOTORROAD)),
            Case(mapOf("motorroad" to "yes", "bicycle" to "yes"), info(flags = BicycleRoadInfo.FLAG_MOTORROAD)),
            Case(mapOf("motorroad" to "no"), info()),
            Case(mapOf("highway" to "steps", "bicycle" to "dismount"), info(BikeAccess.FORBIDDEN)),
            Case(mapOf("highway" to "motorway", "cycleway" to "lane"),
                info(BikeAccess.FORBIDDEN, SideInfo(SideFacility.BIKE_LANE, BikeAccess.ALLOWED),
                    SideInfo(SideFacility.BIKE_LANE, BikeAccess.ALLOWED), BicycleRoadInfo.FLAG_MOTORROAD)),
        )
        check(cases)
    }

    @Test
    fun cyclewayValuesAndOverrides() {
        val values = mapOf(
            "lane" to SideFacility.BIKE_LANE, "track" to SideFacility.BIKE_TRACK,
            "shared_lane" to SideFacility.SHARED_LANE, "share_busway" to SideFacility.SHARED_LANE,
            "shared" to SideFacility.SHARED_LANE, "no" to SideFacility.NONE, "none" to SideFacility.NONE,
            "separate" to SideFacility.NONE, "unknown" to SideFacility.UNKNOWN,
        )
        val cases = mutableListOf<Case>()
        for ((value, facility) in values) {
            val side = SideInfo(facility, if (facility == SideFacility.UNKNOWN) BikeAccess.UNKNOWN else BikeAccess.ALLOWED)
            val flags = if (value == "separate") BicycleRoadInfo.FLAG_SEPARATE_CYCLEWAY else 0
            for (key in listOf("cycleway", "cycleway:both")) {
                cases += Case(mapOf(key to value), info(left = side, right = side, flags = flags))
            }
        }
        val lane = SideInfo(SideFacility.BIKE_LANE, BikeAccess.ALLOWED)
        val track = SideInfo(SideFacility.BIKE_TRACK, BikeAccess.ALLOWED)
        val forbiddenLane = SideInfo(SideFacility.BIKE_LANE, BikeAccess.FORBIDDEN)
        cases += listOf(
            Case(mapOf("cycleway:left" to "lane"), info(left = lane)),
            Case(mapOf("cycleway:right" to "track"), info(right = track)),
            Case(mapOf("cycleway" to "lane", "cycleway:both" to "track", "cycleway:left" to "no"), info(right = track)),
            Case(mapOf("cycleway:both" to "lane", "cycleway:right:bicycle" to "no"), info(left = lane, right = forbiddenLane)),
            Case(mapOf("cycleway" to "lane", "cycleway:both:bicycle" to "no", "cycleway:left:bicycle" to "yes"),
                info(left = lane, right = forbiddenLane)),
            Case(mapOf("cycleway:left" to "lane", "cycleway:left:bicycle" to "dismount"),
                info(left = SideInfo(SideFacility.BIKE_LANE, BikeAccess.DISMOUNT))),
            Case(mapOf("cycleway:right" to "track", "cycleway:right:bicycle" to "private"),
                info(right = SideInfo(SideFacility.BIKE_TRACK, BikeAccess.CONDITIONAL))),
            Case(mapOf("bicycle" to "no", "cycleway" to "separate"),
                info(BikeAccess.FORBIDDEN, flags = BicycleRoadInfo.FLAG_SEPARATE_CYCLEWAY)),
        )
        check(cases)
    }

    @Test
    fun sidewalksRequireTheirOwnBicyclePermission() {
        val sidewalk = SideInfo(SideFacility.SIDEWALK, BikeAccess.UNKNOWN)
        val cases = mutableListOf<Case>()
        for (value in listOf("yes", "both")) cases += Case(mapOf("sidewalk" to value), info(left = sidewalk, right = sidewalk))
        for (value in listOf("no", "none")) cases += Case(mapOf("sidewalk" to value), info())
        cases += listOf(
            Case(mapOf("sidewalk" to "left"), info(left = sidewalk)),
            Case(mapOf("sidewalk" to "right"), info(right = sidewalk)),
            Case(mapOf("sidewalk" to "separate"), info(flags = BicycleRoadInfo.FLAG_SEPARATE_SIDEWALK)),
            Case(mapOf("sidewalk" to "unknown"), info(left = SideInfo(SideFacility.UNKNOWN, BikeAccess.UNKNOWN),
                right = SideInfo(SideFacility.UNKNOWN, BikeAccess.UNKNOWN))),
            Case(mapOf("sidewalk:both" to "yes", "sidewalk:left" to "no"), info(right = sidewalk)),
            Case(mapOf("sidewalk" to "yes", "sidewalk:both" to "no", "sidewalk:right" to "yes"), info(right = sidewalk)),
            Case(mapOf("sidewalk:left" to "separate"), info(flags = BicycleRoadInfo.FLAG_SEPARATE_SIDEWALK)),
            Case(mapOf("bicycle" to "yes", "sidewalk" to "both"), info(left = sidewalk, right = sidewalk)),
            Case(mapOf("bicycle" to "no", "sidewalk" to "both"), info(BikeAccess.FORBIDDEN, sidewalk, sidewalk)),
            // 全体の sidewalk:bicycle は今回の規則の対象外。左右または both の明示のみ使う。
            Case(mapOf("sidewalk" to "both", "sidewalk:bicycle" to "yes"), info(left = sidewalk, right = sidewalk)),
        )
        for ((value, access) in mapOf(
            "yes" to BikeAccess.ALLOWED, "designated" to BikeAccess.ALLOWED, "permissive" to BikeAccess.ALLOWED,
            "no" to BikeAccess.FORBIDDEN, "dismount" to BikeAccess.DISMOUNT, "unknown" to BikeAccess.UNKNOWN,
        )) {
            val side = SideInfo(SideFacility.SIDEWALK, access)
            cases += Case(mapOf("sidewalk" to "both", "sidewalk:both:bicycle" to value), info(left = side, right = side))
            cases += Case(mapOf("sidewalk" to "both", "sidewalk:left:bicycle" to value), info(left = side, right = sidewalk))
            cases += Case(mapOf("sidewalk" to "both", "sidewalk:right:bicycle" to value), info(left = sidewalk, right = side))
        }
        cases += Case(mapOf("sidewalk" to "both", "sidewalk:both:bicycle" to "yes", "sidewalk:left:bicycle" to "no"),
            info(left = SideInfo(SideFacility.SIDEWALK, BikeAccess.FORBIDDEN), right = SideInfo(SideFacility.SIDEWALK, BikeAccess.ALLOWED)))
        check(cases)
    }

    @Test
    fun shouldersAndCompetingFacilities() {
        val shoulder = SideInfo(SideFacility.SHOULDER, BikeAccess.UNKNOWN)
        val sidewalk = SideInfo(SideFacility.SIDEWALK, BikeAccess.UNKNOWN)
        val lane = SideInfo(SideFacility.BIKE_LANE, BikeAccess.ALLOWED)
        check(listOf(
            Case(mapOf("shoulder" to "yes"), info(left = shoulder, right = shoulder)),
            Case(mapOf("shoulder" to "both"), info(left = shoulder, right = shoulder)),
            Case(mapOf("shoulder" to "left"), info(left = shoulder)),
            Case(mapOf("shoulder" to "right"), info(right = shoulder)),
            Case(mapOf("shoulder" to "no"), info()),
            Case(mapOf("shoulder" to "unknown"), info()),
            Case(mapOf("shoulder" to "both", "sidewalk:left" to "yes", "cycleway:right" to "lane"), info(left = sidewalk, right = lane)),
            Case(mapOf("cycleway:left" to "lane", "sidewalk:left" to "yes"), info(left = lane)),
        ))
    }

    @Test
    fun oneWayAndRoundaboutInBothDirections() {
        val cases = mutableListOf<Case>()
        for (oneway in listOf("yes", "true", "1", "-1", "no", "false", "0")) {
            for (reverse in listOf(false, true)) {
                val isOneWay = oneway !in listOf("no", "false", "0")
                val permitted = !isOneWay || (reverse == (oneway == "-1"))
                cases += Case(mapOf("oneway" to oneway), info(
                    if (permitted) BikeAccess.ALLOWED else BikeAccess.FORBIDDEN,
                    flags = if (isOneWay && permitted) BicycleRoadInfo.FLAG_ONEWAY else 0,
                ), reverse)
                cases += Case(mapOf("oneway" to oneway, "oneway:bicycle" to "no"), info(
                    flags = if (!isOneWay) 0 else if (permitted) BicycleRoadInfo.FLAG_ONEWAY else BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED,
                ), reverse)
            }
        }
        cases += listOf(
            Case(mapOf("junction" to "roundabout"), info(flags = BicycleRoadInfo.FLAG_ONEWAY)),
            Case(mapOf("junction" to "roundabout"), info(BikeAccess.FORBIDDEN), true),
            Case(mapOf("junction" to "roundabout", "oneway" to "no"), info(), true),
            Case(mapOf("oneway" to "unknown"), info(BikeAccess.UNKNOWN)),
            Case(mapOf("oneway" to "yes", "oneway:bicycle" to "no", "bicycle" to "no"),
                info(BikeAccess.FORBIDDEN, flags = BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED), true),
            Case(mapOf("oneway" to "yes", "oneway:bicycle" to "no", "highway" to "steps"),
                info(BikeAccess.FORBIDDEN, flags = BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED), true),
        )
        check(cases)
    }

    @Test
    fun oppositeFacilitiesAndDirectionalExceptions() {
        val cases = mutableListOf<Case>()
        for ((value, facility) in mapOf(
            "opposite" to SideFacility.SHARED_LANE, "opposite_lane" to SideFacility.BIKE_LANE, "opposite_track" to SideFacility.BIKE_TRACK,
        )) {
            val tags = mapOf("oneway" to "yes", "cycleway" to value)
            cases += Case(tags, info(flags = BicycleRoadInfo.FLAG_ONEWAY))
            cases += Case(tags, info(left = SideInfo(facility, BikeAccess.ALLOWED), flags = BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED), true)
            cases += Case(mapOf("oneway" to "yes", "cycleway:left" to value),
                info(right = SideInfo(facility, BikeAccess.ALLOWED), flags = BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED), true)
        }
        val lane = SideInfo(SideFacility.BIKE_LANE, BikeAccess.ALLOWED)
        cases += listOf(
            Case(mapOf("oneway" to "yes", "cycleway:right" to "lane", "cycleway:right:oneway" to "-1"),
                info(left = lane, flags = BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED), true),
            Case(mapOf("oneway" to "-1", "cycleway:left" to "lane", "cycleway:left:oneway" to "1"),
                info(left = lane, flags = BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED)),
            Case(mapOf("oneway" to "yes", "cycleway:left" to "lane", "cycleway:left:oneway" to "no"),
                info(right = lane, flags = BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED), true),
            Case(mapOf("oneway" to "yes", "cycleway" to "no", "cycleway:right:oneway" to "-1"), info(BikeAccess.FORBIDDEN), true),
            Case(mapOf("oneway" to "yes", "cycleway" to "opposite_lane", "cycleway:right:bicycle" to "no"),
                info(BikeAccess.FORBIDDEN, SideInfo(SideFacility.BIKE_LANE, BikeAccess.FORBIDDEN),
                    SideInfo(SideFacility.NONE, BikeAccess.FORBIDDEN)), true),
        )
        check(cases)
    }

    @Test
    fun reverseSwapsSidesAndConditionalTagsOnlySetFlags() {
        val tags = mapOf("cycleway:left" to "track", "sidewalk:right" to "yes", "sidewalk:right:bicycle" to "dismount")
        val track = SideInfo(SideFacility.BIKE_TRACK, BikeAccess.ALLOWED)
        val sidewalk = SideInfo(SideFacility.SIDEWALK, BikeAccess.DISMOUNT)
        check(listOf(Case(tags, info(left = track, right = sidewalk)), Case(tags, info(left = sidewalk, right = track), true)))
        val cases = mutableListOf<Case>()
        for (key in listOf("bicycle:conditional", "access:conditional", "vehicle:conditional", "oneway:bicycle:conditional", "cycleway:left:bicycle:conditional")) {
            cases += Case(mapOf(key to "no @ (Mo-Fr)"), info(flags = BicycleRoadInfo.FLAG_CONDITIONAL_TAGS))
            cases += Case(mapOf("bicycle" to "no", key to "yes @ (Mo-Fr)"),
                info(BikeAccess.FORBIDDEN, flags = BicycleRoadInfo.FLAG_CONDITIONAL_TAGS))
        }
        cases += Case(mapOf("segregated" to "yes", "sidewalk" to "separate", "cycleway" to "separate", "bicycle:conditional" to "yes @ (wet)"),
            info(flags = BicycleRoadInfo.FLAG_SEGREGATED or BicycleRoadInfo.FLAG_SEPARATE_SIDEWALK or
                BicycleRoadInfo.FLAG_SEPARATE_CYCLEWAY or BicycleRoadInfo.FLAG_CONDITIONAL_TAGS))
        check(cases)
    }

    @Test
    fun encodingRoundTripsRandomEnumCombinations() {
        val random = Random(20261117)
        repeat(1000) {
            val original = BicycleRoadInfo(
                BikeAccess.entries.random(random),
                SideInfo(SideFacility.entries.random(random), BikeAccess.entries.random(random)),
                SideInfo(SideFacility.entries.random(random), BikeAccess.entries.random(random)),
                random.nextInt(256),
            )
            assertEquals(original, BicycleRoadInfo.decode(original.encode()), "組み合わせ $it")
        }
        // フィールドの配置も固定し、encode/decode が同時に変わる事故を検出する。
        val packed = info(BikeAccess.DISMOUNT, SideInfo(SideFacility.BIKE_LANE, BikeAccess.FORBIDDEN),
            SideInfo(SideFacility.SHOULDER, BikeAccess.UNKNOWN), 0xff)
        assertEquals(3 or (3 shl 3) or (2 shl 6) or (6 shl 9) or (4 shl 12) or (255 shl 15), packed.encode())
        assertFailsWith<IllegalArgumentException> { info(flags = 256).encode() }
    }

    @Test
    fun trunkRoadsAreNationalRoutesAndBicyclesMayUseThem() {
        // 国道2号のような highway=trunk は、日本では自動車専用でない一般道。歩道あり・設備の明示なしが典型。
        val info = classifyBicycleRoad(mapOf("highway" to "trunk", "sidewalk" to "both"), againstWayDirection = false)
        assertEquals(BikeAccess.ALLOWED, info.roadway)
        assertEquals(0, info.flags and BicycleRoadInfo.FLAG_MOTORROAD)
        assertEquals(SideFacility.SIDEWALK, info.left.facility)
        assertEquals(BikeAccess.UNKNOWN, info.left.access)  // 歩道の自転車通行は明示が無ければ不明
        assertEquals(SideFacility.SIDEWALK, info.right.facility)
        // 自動車専用道路の明示があれば不可
        val motorroad = classifyBicycleRoad(mapOf("highway" to "trunk", "motorroad" to "yes"), againstWayDirection = false)
        assertEquals(BikeAccess.FORBIDDEN, motorroad.roadway)
        assertEquals(BicycleRoadInfo.FLAG_MOTORROAD, motorroad.flags and BicycleRoadInfo.FLAG_MOTORROAD)
        // trunk_link も同様
        assertEquals(BikeAccess.ALLOWED, classifyBicycleRoad(mapOf("highway" to "trunk_link"), false).roadway)
    }
}
