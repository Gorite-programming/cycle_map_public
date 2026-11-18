package com.gorite.cyclemap.routing

/** OSM way の並び順に対する、自転車が通れる向き。 */
enum class BicycleDirection { FORWARD_ONLY, REVERSE_ONLY, BOTH }

/**
 * 自転車の明示指定 > 対向自転車設備 > 車両の一方通行 > 環状交差点の暗黙指定。
 * 通行方向だけを解決する。bicycle=no 等のアクセス制限は呼び出し側で別に扱う。
 */
fun resolveBicycleDirection(tags: Map<String, String>): BicycleDirection {
    fun parse(value: String?): BicycleDirection? = when (value) {
        "yes", "true", "1" -> BicycleDirection.FORWARD_ONLY
        "-1" -> BicycleDirection.REVERSE_ONLY
        "no", "false", "0" -> BicycleDirection.BOTH
        else -> null
    }
    parse(tags["oneway:bicycle"])?.let { return it }
    val way = parse(tags["oneway"])
        ?: if (tags["oneway"] == null && tags["junction"] == "roundabout") {
            BicycleDirection.FORWARD_ONLY
        } else BicycleDirection.BOTH
    val oppositeValues = setOf("opposite", "opposite_lane", "opposite_track")
    for (side in listOf("left", "right")) {
        val facility = tags["cycleway:$side"] ?: tags["cycleway:both"] ?: tags["cycleway"]
        val access = tags["cycleway:$side:bicycle"] ?: tags["cycleway:both:bicycle"] ?: tags["cycleway:bicycle"]
        if (access != null && access !in setOf("yes", "designated", "permissive")) continue
        if (facility !in oppositeValues && facility !in setOf("lane", "track", "shared_lane", "share_busway", "shared")) continue
        val direction = parse(tags["cycleway:$side:oneway"] ?: tags["cycleway:both:oneway"] ?: tags["cycleway:oneway"])
        val oppositeSide = tags.containsKey("cycleway:$side") || tags.containsKey("cycleway:both") ||
            (way == BicycleDirection.FORWARD_ONLY && side == "right") ||
            (way == BicycleDirection.REVERSE_ONLY && side == "left")
        val allowsOpposite = when (direction) {
            BicycleDirection.BOTH -> true
            BicycleDirection.FORWARD_ONLY -> way == BicycleDirection.REVERSE_ONLY
            BicycleDirection.REVERSE_ONLY -> way == BicycleDirection.FORWARD_ONLY
            null -> facility in oppositeValues
        }
        if (allowsOpposite && (facility !in oppositeValues || oppositeSide)) return BicycleDirection.BOTH
    }
    return way
}
