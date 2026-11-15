package com.gorite.cyclemap.utils

/** 真北を 0° とし、時計回りに最も近い 8 方位へ丸める。境界は時計回り側に含む。 */
internal fun compassLabel8(bearingDeg: Double): String {
    val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
    val index = ((normalized + 22.5) / 45.0).toInt() % 8
    return listOf("北", "北東", "東", "南東", "南", "南西", "西", "北西")[index]
}
