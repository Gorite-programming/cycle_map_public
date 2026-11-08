package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphNode
import com.gorite.cyclemap.routing.haversineMeters

/**
 * トンネル・橋 (構造物) の上の道路の標高を、地表の標高 (DEM) ではなく両端の標高から補間する。
 *
 * DEM は地表の高さなので、トンネルの途中の点は山の上の高さ、橋の途中の点は谷底の高さになり、
 * 実在しない急勾配や登りが生まれる (広島の一般道トンネルで、道路面とのずれは中央値 31m・最大 346m)。
 * そこで、構造物の way の途中の点は、地上の道路とつながる点 (坑口・橋台) の標高から距離で線形補間する。
 * 複数の way に分かれた長いトンネルも、構造物どうしのつながりをたどって一続きに補間する。
 * 両端の標高が分からない区間 (海の上の橋など) は、不明 (NaN、勾配なし) にする。
 */

/** 構造物 (トンネル・橋) の way か。地表と同じ高さの通路 (building_passage) と、道路の下の暗渠 (culvert) は除く。 */
fun isStructureWay(tags: Map<String, String>): Boolean {
    val tunnel = tags["tunnel"]
    if (tunnel != null && tunnel != "no" && tunnel != "building_passage" && tunnel != "culvert") return true
    val bridge = tags["bridge"]
    return bridge != null && bridge != "no"
}

/**
 * 構造物の way (ノード id の列) と、地上の道路 (構造物でない way) にも含まれる構造物上のノード (= 坑口・橋台)。
 */
class StructureWays(val ways: List<LongArray>, val groundConnectedNodeIds: Set<Long>) {
    companion object {
        /** 道路の way の一覧から作る。[tagsOf] と [nodesOf] で way の中身を取り出す。 */
        fun <W> from(ways: Iterable<W>, tagsOf: (W) -> Map<String, String>, nodesOf: (W) -> LongArray): StructureWays {
            val structureWays = ArrayList<LongArray>()
            val structureNodes = HashSet<Long>()
            for (w in ways) if (isStructureWay(tagsOf(w))) {
                val ids = nodesOf(w)
                structureWays += ids
                ids.forEach { structureNodes += it }
            }
            val grounded = HashSet<Long>()
            if (structureNodes.isNotEmpty()) {
                for (w in ways) if (!isStructureWay(tagsOf(w))) {
                    for (id in nodesOf(w)) if (id in structureNodes) grounded += id
                }
            }
            return StructureWays(structureWays, grounded)
        }
    }
}

/** 補間の結果の集計 (ログ用)。 */
data class StructureElevationStats(val structureNodes: Int, val interpolated: Int, val unknown: Int)

/**
 * [elevations] (ノード id → 標高 m、不明は NaN) のうち、構造物の途中のノードの標高を補間で置き換えた新しい map を返す。
 *
 * - 固定点: 地上の道路ともつながるノード、または構造物の行き止まり (構造物どうしの隣接が 1 以下)。DEM の値をそのまま使う。
 * - それ以外の構造物上のノードは、隣接ノードとの距離の逆数を重みにした平均を反復して解く。
 *   一本道なら、両端の固定点の標高を距離で線形補間した値になる。
 * - 標高が分かる固定点を持たない連結成分は、全体を不明 (NaN) にする。
 * [nodes] に無いノード (グラフの範囲外) は無視する。
 */
fun interpolateStructureElevations(
    elevations: Map<Long, Float>,
    structures: StructureWays,
    nodes: Map<Long, GraphNode>,
): Pair<Map<Long, Float>, StructureElevationStats> {
    // 構造物上の隣接 (無向)。距離 0 の隣接は重みが無限大になるので、最小 0.5m とする。
    val adjacency = HashMap<Long, MutableList<Pair<Long, Double>>>()
    for (ids in structures.ways) {
        for (i in 0 until ids.size - 1) {
            val a = nodes[ids[i]] ?: continue
            val b = nodes[ids[i + 1]] ?: continue
            if (a.id == b.id) continue
            val d = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude).coerceAtLeast(0.5)
            adjacency.getOrPut(a.id) { ArrayList(2) } += b.id to d
            adjacency.getOrPut(b.id) { ArrayList(2) } += a.id to d
        }
    }
    val result = HashMap(elevations)
    if (adjacency.isEmpty()) return result to StructureElevationStats(0, 0, 0)

    fun isFixed(id: Long): Boolean =
        id in structures.groundConnectedNodeIds || adjacency.getValue(id).map { it.first }.distinct().size <= 1

    var interpolated = 0
    var unknown = 0
    val visited = HashSet<Long>()
    for (startId in adjacency.keys) {
        if (!visited.add(startId)) continue
        // 連結成分を集める
        val component = ArrayList<Long>()
        val stack = ArrayDeque<Long>().apply { add(startId) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            component += id
            for ((next, _) in adjacency.getValue(id)) if (visited.add(next)) stack.add(next)
        }
        val free = component.filter { !isFixed(it) }
        if (free.isEmpty()) continue
        val freeSet = free.toHashSet()
        val anchors = component.filter { it !in freeSet }
            .mapNotNull { id -> elevations[id]?.takeIf { !it.isNaN() } }
        if (anchors.isEmpty()) {
            free.forEach { result[it] = Float.NaN }
            unknown += free.size
            continue
        }
        val pathValues = interpolateAlongPath(component, adjacency, freeSet, elevations)
        if (pathValues != null) {
            free.forEach { result[it] = pathValues.getValue(it).toFloat() }
            interpolated += free.size
            continue
        }
        // 枝分かれのある成分はガウス・ザイデル法。初期値は固定点の平均。標高不明の固定点は隣接から外す。
        val value = HashMap<Long, Double>(free.size * 2)
        val start = anchors.average()
        free.forEach { value[it] = start }
        fun known(id: Long): Double? = if (id in freeSet) value.getValue(id) else elevations[id]?.takeIf { !it.isNaN() }?.toDouble()
        var iterations = 0
        while (iterations++ < MAX_ITERATIONS) {
            var maxChange = 0.0
            for (id in free) {
                var sum = 0.0
                var weight = 0.0
                for ((next, d) in adjacency.getValue(id)) {
                    val e = known(next) ?: continue
                    sum += e / d
                    weight += 1.0 / d
                }
                if (weight == 0.0) continue
                val updated = sum / weight
                maxChange = maxOf(maxChange, kotlin.math.abs(updated - value.getValue(id)))
                value[id] = updated
            }
            if (maxChange < TOLERANCE_METERS) break
        }
        free.forEach { result[it] = value.getValue(it).toFloat() }
        interpolated += free.size
    }
    return result to StructureElevationStats(adjacency.size, interpolated, unknown)
}

/**
 * 成分が一本道 (隣接が 2 以下で、両端がある) なら、道なりの距離で補間した自由ノードの標高を返す。
 * 片側にしか標高の分かる固定点が無ければ、その値で一定にする。一本道でなければ null。
 */
private fun interpolateAlongPath(
    component: List<Long>,
    adjacency: Map<Long, List<Pair<Long, Double>>>,
    freeSet: Set<Long>,
    elevations: Map<Long, Float>,
): Map<Long, Double>? {
    fun neighbors(id: Long) = adjacency.getValue(id).distinctBy { it.first }
    if (component.any { neighbors(it).size > 2 }) return null
    val end = component.firstOrNull { neighbors(it).size <= 1 } ?: return null // 環状は対象外
    val order = ArrayList<Long>(component.size)
    val cum = ArrayList<Double>(component.size)
    var prev = -1L
    var cur = end
    var dist = 0.0
    while (true) {
        order += cur
        cum += dist
        val next = neighbors(cur).firstOrNull { it.first != prev } ?: break
        if (next.first == end || order.size > component.size) break
        dist += next.second
        prev = cur
        cur = next.first
    }
    if (order.size != component.size) return null
    fun anchor(i: Int): Double? = if (order[i] in freeSet) null else elevations[order[i]]?.takeIf { !it.isNaN() }?.toDouble()
    val out = HashMap<Long, Double>()
    for (i in order.indices) {
        if (order[i] !in freeSet) continue
        val before = (i - 1 downTo 0).firstOrNull { anchor(it) != null }
        val after = (i + 1 until order.size).firstOrNull { anchor(it) != null }
        out[order[i]] = when {
            before != null && after != null -> {
                val span = cum[after] - cum[before]
                val f = if (span > 0.0) (cum[i] - cum[before]) / span else 0.0
                anchor(before)!! + (anchor(after)!! - anchor(before)!!) * f
            }
            before != null -> anchor(before)!!
            else -> anchor(after!!)!!
        }
    }
    return out
}

private const val MAX_ITERATIONS = 200_000
private const val TOLERANCE_METERS = 0.001
