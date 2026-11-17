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
    /** 下位から車道・左設備・左条件・右設備・右条件に各3ビット、続いて8ビットのフラグ。 */
    fun encode(): Int {
        require(flags and 0xff == flags) { "未定義のフラグは保存できません: $flags" }
        return roadway.ordinal or (left.facility.ordinal shl 3) or (left.access.ordinal shl 6) or
            (right.facility.ordinal shl 9) or (right.access.ordinal shl 12) or (flags shl 15)
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

        fun decode(bits: Int): BicycleRoadInfo {
            fun access(shift: Int) = BikeAccess.entries.getOrNull((bits ushr shift) and 7) ?: BikeAccess.UNKNOWN
            fun facility(shift: Int) = SideFacility.entries.getOrNull((bits ushr shift) and 7) ?: SideFacility.UNKNOWN
            return BicycleRoadInfo(
                access(0), SideInfo(facility(3), access(6)), SideInfo(facility(9), access(12)),
                (bits ushr 15) and 0xff,
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
    for (side in listOf("cycleway", "sidewalk")) {
        if (listOf(side, "$side:both", "$side:left", "$side:right").any { tags[it] == "separate" }) {
            flag(if (side == "cycleway") BicycleRoadInfo.FLAG_SEPARATE_CYCLEWAY else BicycleRoadInfo.FLAG_SEPARATE_SIDEWALK)
        }
    }

    val explicitBicycle = tags["bicycle"]
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
    val oppositeValues = setOf("opposite", "opposite_lane", "opposite_track")
    fun oppositeOnSide(side: String) =
        tags.containsKey("cycleway:$side") || tags.containsKey("cycleway:both") || side == "right"
    val contraflow = bicycleOneway in setOf("no", "false", "0") || listOf("left", "right").any { side ->
        val value = cycleway(side)
        val access = cyclewayAccess(side)?.let(::infrastructureAccess) ?: BikeAccess.ALLOWED
        val sideDirection = cyclewayDirection(side)
        value in setOf("lane", "track", "shared_lane", "share_busway", "shared", "opposite", "opposite_lane", "opposite_track") &&
            access == BikeAccess.ALLOWED &&
            ((value in oppositeValues && againstWayDirection && oppositeOnSide(side)) ||
                sideDirection == "no" || sideDirection == "0" || sideDirection == "false" ||
                (sideDirection == "-1" && againstWayDirection) ||
                (sideDirection in setOf("yes", "true", "1") && !againstWayDirection))
    }
    when {
        direction == null && bicycleOneway !in setOf("no", "false", "0") -> {
            if (roadway == BikeAccess.ALLOWED) roadway = BikeAccess.UNKNOWN
        }
        direction != null && direction != 0 && direction == travelDirection -> flag(BicycleRoadInfo.FLAG_ONEWAY)
        direction != null && direction != 0 -> {
            if (contraflow) flag(BicycleRoadInfo.FLAG_CONTRAFLOW_ALLOWED)
            else roadway = BikeAccess.FORBIDDEN
        }
    }

    fun sideInfo(side: String): SideInfo {
        val cycle = cycleway(side)
        val opposite = cycle in oppositeValues
        // 側指定なしの逆走設備は、日本の左側通行では way 基準の右側にある。
        val cycleFacility = when {
            cycle == null || (opposite && (!againstWayDirection || !oppositeOnSide(side))) -> SideFacility.NONE
            cycle in setOf("no", "none", "separate") -> SideFacility.NONE
            cycle in setOf("lane", "opposite_lane") -> SideFacility.BIKE_LANE
            cycle in setOf("track", "opposite_track") -> SideFacility.BIKE_TRACK
            cycle in setOf("shared_lane", "share_busway", "shared", "opposite") -> SideFacility.SHARED_LANE
            else -> SideFacility.UNKNOWN
        }
        if (cycleFacility != SideFacility.NONE) {
            val access = cyclewayAccess(side)?.let(::infrastructureAccess)
                ?: if (cycleFacility == SideFacility.UNKNOWN) BikeAccess.UNKNOWN else BikeAccess.ALLOWED
            return SideInfo(cycleFacility, access)
        }

        val sidewalk = tags["sidewalk:$side"] ?: tags["sidewalk:both"] ?: tags["sidewalk"]?.let {
            if (it in setOf("left", "right") && it != side) "no" else it
        }
        if (sidewalk != null && sidewalk !in setOf("no", "none", "separate")) {
            val facility = if (sidewalk in setOf("yes", "both", "left", "right")) SideFacility.SIDEWALK else SideFacility.UNKNOWN
            val access = tags["sidewalk:$side:bicycle"] ?: tags["sidewalk:both:bicycle"]
            return SideInfo(facility, access?.let(::infrastructureAccess) ?: BikeAccess.UNKNOWN)
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
