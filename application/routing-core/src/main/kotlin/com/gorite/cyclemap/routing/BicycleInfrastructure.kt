package com.gorite.cyclemap.routing

/** 道路の左右にある設備。永続化に ordinal を使うため、順序は変更しない。 */
enum class SideFacility { NONE, UNKNOWN, SHARED_LANE, BIKE_LANE, BIKE_TRACK, SIDEWALK, SHOULDER }

/** 自転車の通行条件。永続化に ordinal を使うため、順序は変更しない。 */
enum class BikeAccess { ALLOWED, CONDITIONAL, FORBIDDEN, DISMOUNT, UNKNOWN }

data class SideInfo(val facility: SideFacility, val access: BikeAccess)

data class BicycleRoadInfo(
    val roadway: BikeAccess,
    /** 辺の進行方向から見た左側・右側。 */
    val left: SideInfo,
    val right: SideInfo,
    val flags: Int,
) {
    /** 下位から車道3・左設備4・左条件3・右設備4・右条件3・フラグ14ビット。 */
    fun encode(): Int {
        require(roadway.ordinal in 0..7) { "車道の条件が3ビットを超えています" }
        require(left.facility.ordinal in 0..15 && right.facility.ordinal in 0..15) { "設備が4ビットを超えています" }
        require(left.access.ordinal in 0..7 && right.access.ordinal in 0..7) { "設備の条件が3ビットを超えています" }
        require(flags in 0..0x3fff) { "フラグが14ビットを超えています: $flags" }
        return roadway.ordinal or (left.facility.ordinal shl 3) or (left.access.ordinal shl 7) or
            (right.facility.ordinal shl 10) or (right.access.ordinal shl 14) or (flags shl 17)
    }

    companion object {
        const val FLAG_ONEWAY = 1 shl 0
        const val FLAG_CONTRAFLOW_ALLOWED = 1 shl 1
        const val FLAG_USE_SIDEPATH = 1 shl 2
        const val FLAG_SEPARATE_CYCLEWAY = 1 shl 3
        const val FLAG_SEPARATE_SIDEWALK = 1 shl 4
        const val FLAG_SEGREGATED = 1 shl 5
        const val FLAG_MOTORROAD = 1 shl 6
        const val FLAG_CONDITIONAL_TAGS = 1 shl 7
        const val FLAG_SIDEWALK_LEFT = 1 shl 8
        const val FLAG_SIDEWALK_RIGHT = 1 shl 9
        const val FLAG_PEDESTRIAN_SHARED = 1 shl 10

        fun decode(bits: Int): BicycleRoadInfo {
            fun access(shift: Int) = BikeAccess.entries.getOrNull((bits ushr shift) and 7) ?: BikeAccess.UNKNOWN
            fun facility(shift: Int) = SideFacility.entries.getOrNull((bits ushr shift) and 15) ?: SideFacility.UNKNOWN
            return BicycleRoadInfo(
                access(0), SideInfo(facility(3), access(7)), SideInfo(facility(10), access(14)),
                (bits ushr 17) and 0x3fff,
            )
        }
    }
}

/**
 * way の OSM タグを分類する純粋な関数。条件式は評価せず、フラグとして残す。
 * [againstWayDirection] が true なら、way に対して左右を反転し、逆向きの通行条件を返す。
 */
fun classifyBicycleRoad(tags: Map<String, String>, againstWayDirection: Boolean): BicycleRoadInfo {
    var flags = 0
    fun flag(value: Int) { flags = flags or value }
    val highway = tags["highway"]
    // 日本では highway=trunk が国道 (自動車専用でない一般道) なので、自転車は通れる。自動車専用は motorway と motorroad=yes。
    val motorroad = tags["motorroad"] == "yes" || highway in setOf("motorway", "motorway_link")
    if (motorroad) flag(BicycleRoadInfo.FLAG_MOTORROAD)
    if (tags["bicycle"] == "use_sidepath") flag(BicycleRoadInfo.FLAG_USE_SIDEPATH)
    if (tags["segregated"] == "yes") flag(BicycleRoadInfo.FLAG_SEGREGATED)
    if (tags.keys.any { it.endsWith(":conditional") }) flag(BicycleRoadInfo.FLAG_CONDITIONAL_TAGS)
    if (listOf("cycleway", "cycleway:both", "cycleway:left", "cycleway:right").any { tags[it] == "separate" }) {
        flag(BicycleRoadInfo.FLAG_SEPARATE_CYCLEWAY)
    }

    val explicitBicycle = tags["bicycle"]
    if (tags["segregated"] != "yes" && (
        (highway in setOf("footway", "pedestrian", "path") && explicitBicycle in setOf("yes", "designated", "permissive")) ||
            (tags["foot"] in setOf("designated", "yes") && explicitBicycle == "designated")
        )) flag(BicycleRoadInfo.FLAG_PEDESTRIAN_SHARED)
    var roadway = when {
        highway == "steps" -> BikeAccess.FORBIDDEN
        explicitBicycle != null -> infrastructureAccess(explicitBicycle)
        motorroad || highway in setOf("footway", "pedestrian", "corridor") -> BikeAccess.FORBIDDEN
        tags.containsKey("vehicle") -> infrastructureAccess(tags.getValue("vehicle"))
        tags.containsKey("access") -> infrastructureAccess(tags.getValue("access"))
        highway == null || highway in setOf(
            "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link",
            "residential", "service", "unclassified", "living_street", "road", "cycleway",
        ) -> BikeAccess.ALLOWED
        else -> BikeAccess.UNKNOWN
    }
    fun cycleway(side: String): String? = tags["cycleway:$side"] ?: tags["cycleway:both"] ?: tags["cycleway"]
    fun cyclewayAccess(side: String): String? =
        tags["cycleway:$side:bicycle"] ?: tags["cycleway:both:bicycle"] ?: tags["cycleway:bicycle"]
    fun cyclewayDirection(side: String): String? =
        tags["cycleway:$side:oneway"] ?: tags["cycleway:both:oneway"] ?: tags["cycleway:oneway"]
    fun sidewalk(side: String): String? = tags["sidewalk:$side"] ?: tags["sidewalk:both"] ?: tags["sidewalk"]?.let {
        if (it in setOf("left", "right") && it != side) "no" else it
    }
    for (side in listOf("left", "right")) {
        val value = sidewalk(side)
        if (value == "separate") flag(BicycleRoadInfo.FLAG_SEPARATE_SIDEWALK)
        if (value in setOf("yes", "both", "left", "right")) {
            val travelLeft = (side == "left") != againstWayDirection
            flag(if (travelLeft) BicycleRoadInfo.FLAG_SIDEWALK_LEFT else BicycleRoadInfo.FLAG_SIDEWALK_RIGHT)
        }
    }

    // 設備の左右・opposite の配置には元の一方通行の向きを使う。
    // 自転車が通れる向き自体は、下の resolveBicycleDirection だけで決める。
    val wayOneway = tags["oneway"] ?: if (tags["junction"] == "roundabout") "yes" else "no"
    val bicycleOneway = tags["oneway:bicycle"]
    val effectiveOneway = if (bicycleOneway in setOf("yes", "true", "1", "-1")) bicycleOneway else wayOneway
    val direction = when (effectiveOneway) {
        "yes", "true", "1" -> 1
        "-1" -> -1
        "no", "false", "0" -> 0
        else -> null
    }
    val travelDirection = if (againstWayDirection) -1 else 1
    val againstOneway = direction != null && direction != 0 && direction != travelDirection
    val oppositeValues = setOf("opposite", "opposite_lane", "opposite_track")
    fun oppositeOnSide(side: String) =
        tags.containsKey("cycleway:$side") || tags.containsKey("cycleway:both") ||
            (direction == 1 && side == "right") || (direction == -1 && side == "left")
    fun cyclewayUsable(side: String): Boolean {
        if (cycleway(side) in oppositeValues && (!againstOneway || !oppositeOnSide(side))) return false
        return when (cyclewayDirection(side)) {
            "yes", "true", "1" -> travelDirection == 1
            "-1" -> travelDirection == -1
            "no", "false", "0" -> true
            else -> !againstOneway || cycleway(side) in oppositeValues
        }
    }
    val bicycleDirection = resolveBicycleDirection(tags)
    val allowedDirection = when (bicycleDirection) {
        BicycleDirection.BOTH -> true
        BicycleDirection.FORWARD_ONLY -> !againstWayDirection
        BicycleDirection.REVERSE_ONLY -> againstWayDirection
    }
    val bicycleTwoWay = bicycleDirection == BicycleDirection.BOTH && direction != null && direction != 0
    when {
        !allowedDirection -> roadway = BikeAccess.FORBIDDEN
        direction == null && bicycleOneway !in setOf("yes", "true", "1", "-1", "no", "false", "0") -> {
            if (roadway == BikeAccess.ALLOWED) roadway = BikeAccess.UNKNOWN
        }
        else -> {
            if (bicycleDirection != BicycleDirection.BOTH || (bicycleTwoWay && !againstOneway)) {
                flag(BicycleRoadInfo.FLAG_ONEWAY)
            }
            if (bicycleTwoWay && roadway in setOf(BikeAccess.ALLOWED, BikeAccess.CONDITIONAL)) {
                flag(BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED)
            }
        }
    }

    fun sideInfo(side: String): SideInfo {
        val cycle = cycleway(side)
        val cycleFacility = when {
            cycle == null -> SideFacility.NONE
            cycle in setOf("no", "none", "separate") -> SideFacility.NONE
            cycle in setOf("lane", "opposite_lane") -> SideFacility.BIKE_LANE
            cycle in setOf("track", "opposite_track") -> SideFacility.BIKE_TRACK
            cycle in setOf("shared_lane", "share_busway", "shared", "opposite") -> SideFacility.SHARED_LANE
            else -> SideFacility.UNKNOWN
        }
        if (cycleFacility != SideFacility.NONE) {
            // 方向指定に合わない設備は、この辺では車道と同じ条件で扱う。
            if (!cyclewayUsable(side)) return SideInfo(SideFacility.NONE, roadway)
            val access = cyclewayAccess(side)?.let(::infrastructureAccess)
                ?: if (cycleFacility == SideFacility.UNKNOWN) BikeAccess.UNKNOWN else BikeAccess.ALLOWED
            return SideInfo(cycleFacility, access)
        }

        val sidewalk = sidewalk(side)
        if (sidewalk != null && sidewalk !in setOf("no", "none", "separate")) {
            val facility = if (sidewalk in setOf("yes", "both", "left", "right")) SideFacility.SIDEWALK else SideFacility.UNKNOWN
            val access = tags["sidewalk:$side:bicycle"] ?: tags["sidewalk:both:bicycle"] ?: tags["sidewalk:bicycle"]
            return SideInfo(facility, if (facility == SideFacility.SIDEWALK) {
                access?.let(::infrastructureAccess) ?: BikeAccess.UNKNOWN
            } else BikeAccess.UNKNOWN)
        }
        if (tags["shoulder"] in setOf("yes", "both", side)) return SideInfo(SideFacility.SHOULDER, BikeAccess.UNKNOWN)
        return SideInfo(SideFacility.NONE, roadway)
    }

    val left = sideInfo("left")
    val right = sideInfo("right")
    return BicycleRoadInfo(roadway, if (againstWayDirection) right else left, if (againstWayDirection) left else right, flags)
}

/** 未知の値を許可として補完しない。 */
private fun infrastructureAccess(value: String): BikeAccess = when (value) {
    "yes", "designated", "permissive" -> BikeAccess.ALLOWED
    "no", "use_sidepath" -> BikeAccess.FORBIDDEN
    "dismount" -> BikeAccess.DISMOUNT
    "private", "destination", "customers", "delivery" -> BikeAccess.CONDITIONAL
    else -> BikeAccess.UNKNOWN
}
