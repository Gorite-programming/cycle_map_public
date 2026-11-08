package com.gorite.cyclemap.importer

/**
 * 案内に出す道路名を OSM のタグから決める。Google マップの案内と同じく、番号のある国道・県道は
 * 「国道2号」「県道41号」とし、番号の無い道は名前 (name) を使う。どちらも無ければ null。
 * - 国道: network=JP:national、または highway=trunk(_link) で ref がある
 * - 県道: network=JP:prefectural(:…)、または highway=primary/secondary(_link) で ref がある
 */
fun roadDisplayName(tags: Map<String, String>): String? {
    val name = tags["name"]?.trim()?.takeIf { it.isNotEmpty() }
    val ref = tags["ref"]?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    val highway = tags["highway"]?.removeSuffix("_link")
    val network = tags["network"]
    return when {
        ref != null && (network == "JP:national" || (network == null && highway == "trunk")) -> "国道${ref}号"
        ref != null && (network?.startsWith("JP:prefectural") == true ||
            (network == null && (highway == "primary" || highway == "secondary"))) -> "県道${ref}号"
        else -> name
    }
}

/**
 * way の進行方向ごとの車線 (OSM の turn:lanes)。戻り値は (way の向き, 逆向き)。
 * - turn:lanes:forward / turn:lanes:backward があればそれぞれ
 * - 一方通行の道は turn:lanes をその向きに (oneway=-1 は逆向き)
 * - 両方向の道の、向きの付かない turn:lanes は、どちら向きか決められないので使わない
 */
fun turnLanesByDirection(tags: Map<String, String>): Pair<String?, String?> {
    fun clean(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
    val oneway = tags["oneway"]
    val plain = clean(tags["turn:lanes"])
    val forward = clean(tags["turn:lanes:forward"]) ?: if (oneway in setOf("yes", "true", "1")) plain else null
    val backward = clean(tags["turn:lanes:backward"]) ?: if (oneway == "-1") plain else null
    return forward to backward
}
