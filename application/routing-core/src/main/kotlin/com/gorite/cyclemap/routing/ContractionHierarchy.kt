package com.gorite.cyclemap.routing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.PriorityQueue
import kotlin.math.max

/** S1 offline contraction hierarchy builder. Edge storage is primitive and grows geometrically. */
class ContractionHierarchyBuilder(
    private val maxWitnessSettled: Int = DEFAULT_MAX_WITNESS_SETTLED,
    private val maxWitnessCost: Double = DEFAULT_MAX_WITNESS_COST,
) {
    init { require(maxWitnessSettled > 0 && maxWitnessCost > 0.0) }

    data class BuildResult(val hierarchy: ContractionHierarchy, val shortcutCount: Int, val inputEdgeCount: Int, val elapsedMillis: Long)

    /**
     * [progress] を渡すと、約 [PROGRESS_INTERVAL_MILLIS] ミリ秒ごとに、前処理の進み具合 (取り除いたノード数・辺の総数・近道の数・
     * 直前に取り除いたノードの入出次数・迂回路探しの累計回数) を呼ぶ。結果には影響しない。
     */
    fun build(graph: LazyMappedRoadGraph, graphFingerprint: GraphFingerprint, progress: ((String) -> Unit)? = null): BuildResult {
        val started = System.nanoTime()
        val n = graph.nodeCount
        require(graph.edgeCount < Int.MAX_VALUE / 2) { "Graph edge count too large for S1 arrays: ${graph.edgeCount}" }
        var cap = max(16, graph.edgeCount)
        var from = IntArray(cap); var to = IntArray(cap); var middle = IntArray(cap) { -1 }
        var cost = DoubleArray(cap); var dist = DoubleArray(cap); var childA = IntArray(cap) { -1 }; var childB = IntArray(cap) { -1 }
        var nextOut = IntArray(cap); var nextIn = IntArray(cap)
        val headOut = IntArray(n) { -1 }; val headIn = IntArray(n) { -1 }
        var edgeN = 0
        fun reserve() {
            if (edgeN < cap) return
            val newCap = (cap.toLong() * 3 / 2 + 16).coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt()
            check(newCap > cap) { "CH edge count exceeds Int indexed S1 limit" }
            from = from.copyOf(newCap); to = to.copyOf(newCap); middle = middle.copyOf(newCap)
            cost = cost.copyOf(newCap); dist = dist.copyOf(newCap); childA = childA.copyOf(newCap); childB = childB.copyOf(newCap)
            nextOut = nextOut.copyOf(newCap); nextIn = nextIn.copyOf(newCap); cap = newCap
        }
        fun addEdge(a: Int, b: Int, c: Double, d: Double, mid: Int, ca: Int = -1, cb: Int = -1) {
            reserve(); val e = edgeN++
            from[e] = a; to[e] = b; cost[e] = c; dist[e] = d; middle[e] = mid; childA[e] = ca; childB[e] = cb
            nextOut[e] = headOut[a]; headOut[a] = e
            nextIn[e] = headIn[b]; headIn[b] = e
        }
        val cursor = graph.newRoutingEdgeCursor()
        for (u in 0 until n) {
            cursor.reset(u)
            while (cursor.next()) {
                val mult = graph.routingEdgeMultiplier(cursor.type, cursor.grade, RoutePreference.RECOMMENDED, RouteCostMode.BICYCLE)
                if (!mult.isNaN() && cursor.distance >= 0.0) addEdge(u, cursor.target, cursor.distance * mult, cursor.distance, -1)
            }
        }
        val originalCount = edgeN
        val rank = IntArray(n) { -1 }; val deletedNeighbors = IntArray(n); val depth = IntArray(n)
        val active = BooleanArray(n) { true }
        val queue = PriorityQueue<QueueNode>(compareBy<QueueNode> { it.priority }.thenBy { it.node })
        fun priority(v: Int): Double {
            var incoming = 0; var outgoing = 0
            var e = headIn[v]; while (e >= 0) { if (active[from[e]]) incoming++; e = nextIn[e] }
            e = headOut[v]; while (e >= 0) { if (active[to[e]]) outgoing++; e = nextOut[e] }
            return (incoming * outgoing - incoming - outgoing).toDouble() + deletedNeighbors[v] * 2.0 + depth[v] * 0.5
        }
        for (v in 0 until n) queue += QueueNode(v, priority(v), 0)
        var shortcuts = 0; var contracted = 0
        val witness = WitnessWorkspace(n)
        val bestIn = IntArray(n); val bestOut = IntArray(n); val bestStampIn = IntArray(n); val bestStampOut = IntArray(n)
        var witnessSearches = 0L; var lastProgress = System.nanoTime(); var maxIns = 0; var maxOuts = 0
        while (queue.isNotEmpty()) {
            val item = queue.remove(); val v = item.node
            if (!active[v]) continue
            val p = priority(v)
            if (kotlin.math.abs(p - item.priority) > 1e-9) { queue += QueueNode(v, p, item.version + 1); continue }
            // Collect active incoming and outgoing edges without allocating edge objects.
            // 同じ相手への平行な辺 (近道が積み重なってできる) は、いちばん安い 1 本だけ残す。
            // 高い方の辺を通る最短経路は無いので結果は変わらない。残さないと、最後に残る少数のノードに
            // 平行な辺が何十万本も集まり、入る辺 x 出る辺 の組み合わせが爆発して前処理が終わらない (茨城・神奈川)。
            val ins = IntArrayList(); var e = headIn[v]
            while (e >= 0) {
                val u = from[e]
                if (active[u]) { if (bestStampIn[u] != contracted + 1 || cost[e] < cost[bestIn[u]]) { bestStampIn[u] = contracted + 1; bestIn[u] = e } }
                e = nextIn[e]
            }
            e = headIn[v]
            while (e >= 0) { val u = from[e]; if (active[u] && bestIn[u] == e && bestStampIn[u] == contracted + 1) ins.add(e); e = nextIn[e] }
            val outs = IntArrayList(); e = headOut[v]
            while (e >= 0) {
                val w = to[e]
                if (active[w]) { if (bestStampOut[w] != contracted + 1 || cost[e] < cost[bestOut[w]]) { bestStampOut[w] = contracted + 1; bestOut[w] = e } }
                e = nextOut[e]
            }
            e = headOut[v]
            while (e >= 0) { val w = to[e]; if (active[w] && bestOut[w] == e && bestStampOut[w] == contracted + 1) outs.add(e); e = nextOut[e] }
            // Do not publish shortcuts until every witness search for v is done.
            // A shortcut created for an earlier incoming edge may itself pass
            // through v; exposing it immediately would let later witness searches
            // incorrectly use a path that the excluded node is supposed to avoid.
            val pendingFrom = IntArrayList(); val pendingTo = IntArrayList()
            val pendingCost = DoubleArrayList(); val pendingDistance = DoubleArrayList()
            val pendingChildA = IntArrayList(); val pendingChildB = IntArrayList()
            if (progress != null) {
                if (ins.size > maxIns) maxIns = ins.size
                if (outs.size > maxOuts) maxOuts = outs.size
                val now = System.nanoTime()
                if (now - lastProgress > PROGRESS_INTERVAL_MILLIS * 1_000_000L) {
                    lastProgress = now
                    progress("contracted=$contracted/$n edges=$edgeN shortcuts=$shortcuts witnessSearches=$witnessSearches queue=${queue.size} lastIns=${ins.size} lastOuts=${outs.size} maxIns=$maxIns maxOuts=$maxOuts")
                }
            }
            for (i in 0 until ins.size) {
                val ie = ins[i]; val u = from[ie]
                witnessSearches++
                witness.search(u, v, maxWitnessCost, active, headOut, to, cost, nextOut, edgeN, maxWitnessSettled, maxWitnessCost)
                for (j in 0 until outs.size) {
                    val oe = outs[j]; val w = to[oe]
                    if (u == w) continue
                    val candidate = cost[ie] + cost[oe]
                    if (witness.distanceTo(w) > candidate) {
                        pendingFrom.add(u); pendingTo.add(w); pendingCost.add(candidate); pendingDistance.add(dist[ie] + dist[oe]); pendingChildA.add(ie); pendingChildB.add(oe)
                    }
                }
            }
            for (i in 0 until pendingFrom.size) {
                addEdge(pendingFrom[i], pendingTo[i], pendingCost[i], pendingDistance[i], v, pendingChildA[i], pendingChildB[i]); shortcuts++
            }
            active[v] = false; rank[v] = contracted++
            for (i in 0 until ins.size) { val u = from[ins[i]]; if (active[u]) { deletedNeighbors[u]++; depth[u] = max(depth[u], depth[v] + 1); queue += QueueNode(u, priority(u), contracted) } }
            for (i in 0 until outs.size) { val w = to[outs[i]]; if (active[w]) { deletedNeighbors[w]++; depth[w] = max(depth[w], depth[v] + 1); queue += QueueNode(w, priority(w), contracted) } }
        }
        val hierarchy = ContractionHierarchy.create(n, edgeN, rank, from, to, cost, dist, middle, childA, childB, graphFingerprint)
        return BuildResult(hierarchy, shortcuts, originalCount, (System.nanoTime() - started) / 1_000_000)
    }

    private data class QueueNode(val node: Int, val priority: Double, val version: Int)
    private class IntArrayList { private var a = IntArray(8); var size = 0; private set; operator fun get(i: Int) = a[i]; fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v } }
    private class DoubleArrayList { private var a = DoubleArray(8); var size = 0; private set; operator fun get(i: Int) = a[i]; fun add(v: Double) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v } }

    private class WitnessWorkspace(n: Int) {
        private val dist = DoubleArray(n); private val stamp = IntArray(n); private var generation = 0
        private val heap = PriorityQueue<WitnessEntry>(compareBy<WitnessEntry> { it.cost }.thenBy { it.node })
        private var settledCostLimit = 0.0
        fun search(source: Int, excluded: Int, maxTargetCost: Double, active: BooleanArray, heads: IntArray, targets: IntArray, costs: DoubleArray, next: IntArray, edgeCount: Int, maxSettled: Int, maxCost: Double) {
            generation++; if (generation == 0) { stamp.fill(0); generation = 1 }
            heap.clear(); set(source, 0.0); heap += WitnessEntry(source, 0.0)
            settledCostLimit = minOf(maxTargetCost, maxCost); var settled = 0
            while (heap.isNotEmpty() && settled < maxSettled) {
                val cur = heap.remove(); if (get(cur.node) != cur.cost) continue
                if (cur.cost > settledCostLimit) break
                settled++
                if (cur.node == excluded) continue
                var e = heads[cur.node]
                while (e >= 0 && e < edgeCount) {
                    val w = targets[e]
                    if (w != excluded && active[w]) { val nc = cur.cost + costs[e]; if (nc <= settledCostLimit && nc < get(w)) { set(w, nc); heap += WitnessEntry(w, nc) } }
                    e = next[e]
                }
            }
        }
        private fun set(n: Int, d: Double) { stamp[n] = generation; dist[n] = d }
        private fun get(n: Int) = if (stamp[n] == generation) dist[n] else Double.POSITIVE_INFINITY
        fun distanceTo(n: Int) = get(n)
        private data class WitnessEntry(val node: Int, val cost: Double)
    }

    companion object {
        const val DEFAULT_MAX_WITNESS_SETTLED = 100
        const val DEFAULT_MAX_WITNESS_COST = 1_000_000.0
        const val PROGRESS_INTERVAL_MILLIS = 5_000L
    }
}

data class ChRouteResult(val nodeIndices: IntArray, val totalCost: Double, val totalDistance: Double) {
    val isReachable get() = nodeIndices.isNotEmpty()
}

/** File-level identity stored with a hierarchy so it cannot be paired with another graph. */
data class GraphFingerprint(val size: Long, val firstCrc: Long, val lastCrc: Long) {
    companion object {
        val UNKNOWN = GraphFingerprint(-1L, 0L, 0L)
        fun fromFile(file: File): GraphFingerprint = FileChannel.open(file.toPath()).use { channel ->
            val size = channel.size(); val chunk = minOf(size, 1L shl 20).toInt()
            fun crc(position: Long): Long {
                val crc = CRC32(); val buffer = ByteBuffer.allocate(chunk); var p = position
                while (buffer.hasRemaining()) { val n = channel.read(buffer, p); if (n <= 0) break; p += n }
                crc.update(buffer.array(), 0, buffer.position()); return crc.value
            }
            GraphFingerprint(size, crc(0), crc((size - chunk).coerceAtLeast(0)))
        }
    }
}

/** Read access to hierarchy arrays: heap arrays (read/create) or a memory-mapped file (open). */
internal interface ChStore {
    val upCount: Int
    val downCount: Int
    fun rank(v: Int): Int
    fun off(v: Int): Int
    fun roff(v: Int): Int
    fun upT(e: Int): Int
    fun upC(e: Int): Double
    fun upM(e: Int): Int
    fun downT(e: Int): Int
    fun downC(e: Int): Double
    fun downM(e: Int): Int
}

private class ArrayChStore(
    val rank: IntArray,
    val offsets: IntArray, val upTargets: IntArray, val upCosts: DoubleArray, val upMiddles: IntArray,
    val reverseOffsets: IntArray, val downTargets: IntArray, val downCosts: DoubleArray, val downMiddles: IntArray,
) : ChStore {
    override val upCount get() = upTargets.size
    override val downCount get() = downTargets.size
    override fun rank(v: Int) = rank[v]
    override fun off(v: Int) = offsets[v]
    override fun roff(v: Int) = reverseOffsets[v]
    override fun upT(e: Int) = upTargets[e]
    override fun upC(e: Int) = upCosts[e]
    override fun upM(e: Int) = upMiddles[e]
    override fun downT(e: Int) = downTargets[e]
    override fun downC(e: Int) = downCosts[e]
    override fun downM(e: Int) = downMiddles[e]
}

/** Zero-heap view over the VERSION 2 file layout (big-endian). Offsets are validated at open time. */
private class MappedChStore(private val buf: java.nio.MappedByteBuffer, n: Int, override val upCount: Int, override val downCount: Int) : ChStore {
    private val rankBase = HEADER_BYTES
    private val offBase = rankBase + 4 * n
    private val roffBase = offBase + 4 * (n + 1)
    private val upBase = roffBase + 4 * (n + 1)
    private val downBase = upBase + 16 * upCount
    val endOffset = downBase + 16 * downCount
    override fun rank(v: Int) = buf.getInt(rankBase + 4 * v)
    override fun off(v: Int) = buf.getInt(offBase + 4 * v)
    override fun roff(v: Int) = buf.getInt(roffBase + 4 * v)
    override fun upT(e: Int) = buf.getInt(upBase + 16 * e)
    override fun upC(e: Int) = buf.getDouble(upBase + 16 * e + 4)
    override fun upM(e: Int) = buf.getInt(upBase + 16 * e + 12)
    override fun downT(e: Int) = buf.getInt(downBase + 16 * e)
    override fun downC(e: Int) = buf.getDouble(downBase + 16 * e + 4)
    override fun downM(e: Int) = buf.getInt(downBase + 16 * e + 12)
    companion object { const val HEADER_BYTES = 44 }
}

/** Compact hierarchy: exactly one 16-byte record per arc across upward and downward CSR. Use [open] for a mmap-backed instance. */
class ContractionHierarchy private constructor(
    val nodeCount: Int,
    private val store: ChStore,
    private val fingerprint: GraphFingerprint,
    private val channel: FileChannel? = null,
) : AutoCloseable {
    val edgeCount: Int get() = store.upCount
    // 作業用の配列はノード数に比例して大きい (県単位で約 60MB と 70MB)。CH 本体は mmap でヒープを使わないので、
    // これが「CH のヒープ負担」のほぼ全部。使う直前に確保し、[releaseWorkspaces] で手放せるようにする。
    private var queryWs: QueryWorkspace? = null

    /** mmap 版では参照を捨てるだけ (MappedByteBuffer は明示解放できない)。ヒープ版では何もしない。 */
    override fun close() { channel?.close() }

    /**
     * 探索用の作業配列を手放す (次の問い合わせで確保し直す)。県またぎの経路で、段ごとに使い終わった CH の分を
     * 先に返し、ほかの県のグラフと同時にヒープへ載る量を減らすために呼ぶ。結果には影響しない。
     */
    @Synchronized fun releaseWorkspaces() { queryWs = null; manyWs = null }

    @Synchronized fun route(start: Int, goal: Int, graph: LazyMappedRoadGraph): ChRouteResult {
        require(start in 0 until nodeCount && goal in 0 until nodeCount && graph.nodeCount == nodeCount)
        if (start == goal) return ChRouteResult(intArrayOf(start), 0.0, 0.0)
        val r0 = System.nanoTime(); var settledR = 0
        val ws = queryWs ?: QueryWorkspace(nodeCount).also { queryWs = it }
        ws.begin(); ws.setF(start, 0.0, -1); ws.setB(goal, 0.0, -1); ws.pushF(start, 0.0); ws.pushB(goal, 0.0)
        var best = Double.POSITIVE_INFINITY; var meet = -1
        while (true) {
            val expandF = ws.hasF() && ws.minF() < best; val expandB = ws.hasB() && ws.minB() < best
            if (!expandF && !expandB) break
            settledR++
            if (expandF && (!expandB || ws.minF() <= ws.minB())) {
                val (v, d) = ws.popF(); if (d > ws.fCost(v)) continue
                if (d + ws.bCost(v) < best) { best = d + ws.bCost(v); meet = v }
                for (e in store.off(v) until store.off(v + 1)) { val w = store.upT(e); val nd = d + store.upC(e); if (nd < ws.fCost(w)) { ws.setF(w, nd, e); ws.pushF(w, nd); if (nd + ws.bCost(w) < best) { best = nd + ws.bCost(w); meet = w } } }
            } else {
                val (v, d) = ws.popB(); if (d > ws.bCost(v)) continue
                if (d + ws.fCost(v) < best) { best = d + ws.fCost(v); meet = v }
                for (e in store.roff(v) until store.roff(v + 1)) { val w = store.downT(e); val nd = d + store.downC(e); if (nd < ws.bCost(w)) { ws.setB(w, nd, e); ws.pushB(w, nd); if (nd + ws.fCost(w) < best) { best = nd + ws.fCost(w); meet = w } } }
            }
        }
        if (meet < 0 || best.isInfinite()) return ChRouteResult(IntArray(0), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val r1 = System.nanoTime()
        val path = IntArrayList(); path.add(start); var v = meet; var guard = 0
        val upPath = IntArrayList()
        while (v != start) { check(++guard <= nodeCount); val e = ws.fEdge(v); check(e >= 0); upPath.add(e); v = edgeSource(e) }
        for (i in upPath.size - 1 downTo 0) unpack(edgeSource(upPath[i]), store.upT(upPath[i]), store.upM(upPath[i]), graph, path)
        v = meet; guard = 0
        while (v != goal) { check(++guard <= nodeCount); val e = ws.bEdge(v); check(e >= 0); val low = downOwner(e); unpack(v, low, store.downM(e), graph, path); v = low }
        val r2 = System.nanoTime()
        var totalDistance = 0.0
        val cursor = graph.newRoutingEdgeCursor(lazyTypes = true)
        for (i in 0 until path.size - 1) {
            val a = path[i]; val b = path[i + 1]; cursor.reset(a); var bestCost = Double.POSITIVE_INFINITY; var selectedDistance = Double.POSITIVE_INFINITY
            while (cursor.next()) if (cursor.target == b) {
                val multiplier = graph.routingEdgeMultiplier(cursor.type, cursor.grade, RoutePreference.RECOMMENDED, RouteCostMode.BICYCLE)
                val c = cursor.distance * multiplier
                if (!multiplier.isNaN() && (c < bestCost || (c == bestCost && cursor.distance < selectedDistance))) { bestCost = c; selectedDistance = cursor.distance }
            }
            check(selectedDistance.isFinite()) { "Expanded CH arc $a->$b missing in source graph" }; totalDistance += selectedDistance
        }
        lastRouteStats = "searchMs=${(r1 - r0) / 1_000_000} iter=$settledR unpackMs=${(r2 - r1) / 1_000_000} distMs=${(System.nanoTime() - r2) / 1_000_000} nodes=${path.size}"
        return ChRouteResult(path.toArray(), best, totalDistance)
    }

    @Volatile internal var lastRouteStats: String = ""

    /** [searchMany] の結果。bestSource は sources 配列の添字 (到達不能は -1)。 */
    class ManyResult(val bestCost: DoubleArray, val bestSource: IntArray)

    private var manyWs: ManyWorkspace? = null

    /**
     * 多対多プリミティブ。各 target t について min over s of (sourceCosts[s] + dist(s, t)) と arg-min を返す。
     * 1 回の多始点・上向き前向き探索 (全 source を初期コスト付きで投入、早期終了なし) の後、target ごとに
     * 下向き (後ろ向き) 探索を行い、前向きコストが確定済みのノードで合流候補を評価する。
     */
    @Synchronized fun searchMany(sources: IntArray, sourceCosts: DoubleArray, targets: IntArray): ManyResult {
        require(sources.size == sourceCosts.size)
        val bestCost = DoubleArray(targets.size) { Double.POSITIVE_INFINITY }
        val bestSource = IntArray(targets.size) { -1 }
        if (sources.isEmpty() || targets.isEmpty()) return ManyResult(bestCost, bestSource)
        val t0 = System.nanoTime()
        val w = manyWs ?: ManyWorkspace(nodeCount).also { manyWs = it }
        val t1 = System.nanoTime(); var settledF = 0
        w.beginForward()
        for (i in sources.indices) {
            val s = sources[i]; require(s in 0 until nodeCount); val c = sourceCosts[i]
            if (c < w.f(s)) { w.setF(s, c, i); w.pushF(s, c) }
        }
        while (!w.emptyF()) {
            val v = w.popF(); val d = w.poppedCost
            if (d > w.f(v)) continue
            val seed = w.seed(v); settledF++
            for (e in store.off(v) until store.off(v + 1)) {
                val x = store.upT(e); val nd = d + store.upC(e)
                if (nd < w.f(x)) { w.setF(x, nd, seed); w.pushF(x, nd) }
            }
        }
        val t2 = System.nanoTime()
        // RPHAST: targets から下向き弧を逆にたどった閉包 U を求める
        w.beginSweep()
        for (k in targets.indices) { val t = targets[k]; require(t in 0 until nodeCount); w.pushU(t) }
        var sweepArcs = 0L
        while (w.stackSize > 0) {
            val v = w.popU()
            for (e in store.roff(v) until store.roff(v + 1)) w.pushU(store.downT(e))
        }
        val unionSize = w.unionSize
        w.packUnion { v -> store.rank(v) }
        val t3 = System.nanoTime()
        w.sortUnion()
        val t4 = System.nanoTime()
        for (i in unionSize - 1 downTo 0) {
            val v = w.unionNode(i)
            var bd = w.f(v); var bs = if (bd.isFinite()) w.seed(v) else -1
            val lo = store.roff(v); val hi = store.roff(v + 1); sweepArcs += hi - lo
            for (e in lo until hi) {
                val u = store.downT(e); val du = w.d(u)
                if (du.isInfinite()) continue
                val nd = du + store.downC(e)
                if (nd < bd) { bd = nd; bs = w.dSeed(u) }
            }
            w.setD(v, bd, bs)
        }
        for (k in targets.indices) { val t = targets[k]; bestCost[k] = w.d(t); bestSource[k] = if (bestCost[k].isFinite()) w.dSeed(t) else -1 }
        val t5 = System.nanoTime()
        lastManyStats = "sources=${sources.size} targets=${targets.size} allocMs=${(t1 - t0) / 1_000_000} fwdMs=${(t2 - t1) / 1_000_000} settledF=$settledF unionSize=$unionSize sweepArcs=$sweepArcs unionMs=${(t3 - t2) / 1_000_000} sortMs=${(t4 - t3) / 1_000_000} sweepMs=${(t5 - t4) / 1_000_000}"
        return ManyResult(bestCost, bestSource)
    }

    /** 旧実装 (target ごとの後ろ向きダイクストラ)。テストのオラクルと計測の比較用。 */
    @Synchronized internal fun searchManyDijkstra(sources: IntArray, sourceCosts: DoubleArray, targets: IntArray): ManyResult {
        require(sources.size == sourceCosts.size)
        val bestCost = DoubleArray(targets.size) { Double.POSITIVE_INFINITY }
        val bestSource = IntArray(targets.size) { -1 }
        if (sources.isEmpty() || targets.isEmpty()) return ManyResult(bestCost, bestSource)
        val t0 = System.nanoTime()
        val w = manyWs ?: ManyWorkspace(nodeCount).also { manyWs = it }
        val t1 = System.nanoTime(); var settledF = 0; var settledB = 0L
        w.beginForward()
        for (i in sources.indices) {
            val s = sources[i]; require(s in 0 until nodeCount); val c = sourceCosts[i]
            if (c < w.f(s)) { w.setF(s, c, i); w.pushF(s, c) }
        }
        while (!w.emptyF()) {
            val v = w.popF(); val d = w.poppedCost
            if (d > w.f(v)) continue
            val seed = w.seed(v); settledF++
            for (e in store.off(v) until store.off(v + 1)) {
                val x = store.upT(e); val nd = d + store.upC(e)
                if (nd < w.f(x)) { w.setF(x, nd, seed); w.pushF(x, nd) }
            }
        }
        val t2 = System.nanoTime()
        for (k in targets.indices) {
            val t = targets[k]; require(t in 0 until nodeCount)
            w.beginBackward(); w.setB(t, 0.0); w.pushB(t, 0.0)
            var best = Double.POSITIVE_INFINITY; var bestSeed = -1
            while (!w.emptyB()) {
                val v = w.popB(); val d = w.poppedCost
                if (d > w.b(v)) continue
                if (d >= best) break
                settledB++
                val fv = w.f(v)
                if (fv + d < best) { best = fv + d; bestSeed = w.seed(v) }
                for (e in store.roff(v) until store.roff(v + 1)) {
                    val x = store.downT(e); val nd = d + store.downC(e)
                    if (nd < w.b(x)) { w.setB(x, nd); w.pushB(x, nd) }
                }
            }
            bestCost[k] = best; bestSource[k] = bestSeed
        }
        val t3 = System.nanoTime()
        lastManyStats = "sources=${sources.size} targets=${targets.size} allocMs=${(t1 - t0) / 1_000_000} fwdMs=${(t2 - t1) / 1_000_000} settledF=$settledF bwdMs=${(t3 - t2) / 1_000_000} settledB=$settledB"
        return ManyResult(bestCost, bestSource)
    }

    @Volatile internal var lastManyStats: String = ""

    private class ManyWorkspace(n: Int) {
        private val nodeCount = n
        private val genF = IntArray(n); private var gf = 0; private var gb = 0
        private val cf = DoubleArray(n); private val sd = IntArray(n)
        // 後ろ向きの配列は旧実装 (searchManyDijkstra) だけが使う。本番の searchMany では確保しない (12 バイト/ノードの節約)。
        private var genB: IntArray? = null; private var cb: DoubleArray? = null
        private val qf = Heap(); private val qb = Heap()
        private val genU = IntArray(n); private var gu = 0
        private val genD = IntArray(n); private val cd = DoubleArray(n); private val sdd = IntArray(n)
        private var stack = IntArray(1024); var stackSize = 0; private set
        private var union = LongArray(1024); var unionSize = 0; private set
        fun beginSweep() {
            gu++; if (gu == 0) { genU.fill(0); genD.fill(0); gu = 1 }
            stackSize = 0; unionSize = 0
        }
        fun pushU(v: Int) {
            if (genU[v] == gu) return
            genU[v] = gu
            if (stackSize == stack.size) stack = stack.copyOf(stackSize * 2)
            stack[stackSize++] = v
            if (unionSize == union.size) union = union.copyOf(unionSize * 2)
            union[unionSize++] = v.toLong()
        }
        fun popU(): Int = stack[--stackSize]
        inline fun packUnion(rank: (Int) -> Int) { for (i in 0 until unionSize) setUnion(i, (rank(unionRaw(i).toInt()).toLong() shl 32) or unionRaw(i)) }
        fun unionRaw(i: Int) = union[i]
        fun setUnion(i: Int, x: Long) { union[i] = x }
        fun sortUnion() = java.util.Arrays.sort(union, 0, unionSize)
        fun unionNode(i: Int) = (union[i] and 0xFFFFFFFFL).toInt()
        fun d(v: Int) = if (genD[v] == gu) cd[v] else Double.POSITIVE_INFINITY
        fun dSeed(v: Int) = sdd[v]
        fun setD(v: Int, c: Double, seed: Int) { genD[v] = gu; cd[v] = c; sdd[v] = seed }
        val poppedCost get() = lastPopped
        private var lastPopped = 0.0
        fun beginForward() { gf++; if (gf == 0) { genF.fill(0); gf = 1 }; qf.clear() }
        fun beginBackward() {
            if (genB == null) { genB = IntArray(nodeCount); cb = DoubleArray(nodeCount) }
            gb++; if (gb == 0) { genB!!.fill(0); gb = 1 }; qb.clear()
        }
        fun f(v: Int) = if (genF[v] == gf) cf[v] else Double.POSITIVE_INFINITY
        fun b(v: Int) = if (genB!![v] == gb) cb!![v] else Double.POSITIVE_INFINITY
        fun seed(v: Int) = sd[v]
        fun setF(v: Int, c: Double, seed: Int) { genF[v] = gf; cf[v] = c; sd[v] = seed }
        fun setB(v: Int, c: Double) { genB!![v] = gb; cb!![v] = c }
        fun pushF(v: Int, c: Double) = qf.add(v, c); fun pushB(v: Int, c: Double) = qb.add(v, c)
        fun emptyF() = qf.size == 0; fun emptyB() = qb.size == 0
        fun popF(): Int { val v = qf.remove(); lastPopped = qf.removed; return v }
        fun popB(): Int { val v = qb.remove(); lastPopped = qb.removed; return v }
        private class Heap {
            private var nodes = IntArray(64); private var keys = DoubleArray(64); var size = 0; private set; var removed = 0.0; private set
            fun clear() { size = 0 }
            fun add(node: Int, key: Double) {
                if (size == nodes.size) { nodes = nodes.copyOf(size * 2); keys = keys.copyOf(size * 2) }
                var i = size++
                while (i > 0) { val p = (i - 1) ushr 1; if (keys[p] <= key) break; nodes[i] = nodes[p]; keys[i] = keys[p]; i = p }
                nodes[i] = node; keys[i] = key
            }
            fun remove(): Int {
                val result = nodes[0]; removed = keys[0]; val ln = nodes[--size]; val lk = keys[size]
                if (size > 0) {
                    var i = 0
                    while (true) { val l = i * 2 + 1; if (l >= size) break; val r = l + 1; val c = if (r < size && keys[r] < keys[l]) r else l; if (keys[c] >= lk) break; nodes[i] = nodes[c]; keys[i] = keys[c]; i = c }
                    nodes[i] = ln; keys[i] = lk
                }
                return result
            }
        }
    }

    private fun edgeSource(edge: Int): Int { var lo = 0; var hi = nodeCount; while (lo < hi) { val mid = (lo + hi) ushr 1; if (store.off(mid + 1) <= edge) lo = mid + 1 else hi = mid }; return lo }
    private fun downOwner(edge: Int): Int { var lo = 0; var hi = nodeCount; while (lo < hi) { val mid = (lo + hi) ushr 1; if (store.roff(mid + 1) <= edge) lo = mid + 1 else hi = mid }; return lo }
    private fun unpack(from: Int, to: Int, middle: Int, graph: LazyMappedRoadGraph, out: IntArrayList) {
        val stackA = IntArrayList(); val stackB = IntArrayList(); val stackMid = IntArrayList(); stackA.add(from); stackB.add(to); stackMid.add(middle)
        var steps = 0
        while (stackA.size > 0) {
            check(++steps < 50_000_000) { "Shortcut expansion exceeded limit" }
            val a = stackA.removeLast(); val b = stackB.removeLast(); val m = stackMid.removeLast()
            if (m < 0) { out.add(b); continue }
            val left = findDown(m, a); val right = findUp(m, b)
            check(left >= 0 && right >= 0) { "Missing shortcut child for $a->$b via $m" }
            stackA.add(m); stackB.add(b); stackMid.add(store.upM(right))
            stackA.add(a); stackB.add(m); stackMid.add(store.downM(left))
        }
    }
    private fun findUp(owner: Int, target: Int): Int { var selected = -1; for (e in store.off(owner) until store.off(owner + 1)) if (store.upT(e) == target && (selected < 0 || store.upC(e) < store.upC(selected))) selected = e; return selected }
    private fun findDown(owner: Int, target: Int): Int { var selected = -1; for (e in store.roff(owner) until store.roff(owner + 1)) if (store.downT(e) == target && (selected < 0 || store.downC(e) < store.downC(selected))) selected = e; return selected }

    private class QueryWorkspace(n: Int) {
        private val genF = IntArray(n); private val genB = IntArray(n); private var generation = 0; private val cf = DoubleArray(n); private val cb = DoubleArray(n); private val pf = IntArray(n); private val pb = IntArray(n); private val qf = MinHeap(); private val qb = MinHeap()
        fun begin() { generation++; if (generation == 0) { genF.fill(0); genB.fill(0); generation = 1 }; qf.clear(); qb.clear() }
        fun setF(n: Int, d: Double, e: Int) { genF[n] = generation; cf[n] = d; pf[n] = e }; fun setB(n: Int, d: Double, e: Int) { genB[n] = generation; cb[n] = d; pb[n] = e }
        fun fCost(n: Int) = if (genF[n] == generation) cf[n] else Double.POSITIVE_INFINITY; fun bCost(n: Int) = if (genB[n] == generation) cb[n] else Double.POSITIVE_INFINITY
        fun fEdge(n: Int) = if (genF[n] == generation) pf[n] else -1; fun bEdge(n: Int) = if (genB[n] == generation) pb[n] else -1
        fun pushF(n: Int, d: Double) = qf.add(n, d); fun pushB(n: Int, d: Double) = qb.add(n, d); fun hasF() = !qf.isEmpty; fun hasB() = !qb.isEmpty; fun minF() = qf.minCost; fun minB() = qb.minCost
        fun popF() = qf.remove().let { it to qf.removedCost }; fun popB() = qb.remove().let { it to qb.removedCost }
        private class MinHeap { private var nodes = IntArray(32); private var keys = DoubleArray(32); private var size = 0; var removedCost = 0.0; private set; val isEmpty get() = size == 0; val minCost get() = if (size == 0) Double.POSITIVE_INFINITY else keys[0]; fun clear() { size = 0 }; fun add(node: Int, key: Double) { if (size == nodes.size) { nodes = nodes.copyOf(size * 2); keys = keys.copyOf(size * 2) }; var i = size++; while (i > 0) { val p = (i - 1) ushr 1; if (keys[p] <= key) break; nodes[i] = nodes[p]; keys[i] = keys[p]; i = p }; nodes[i] = node; keys[i] = key }; fun remove(): Int { val result = nodes[0]; removedCost = keys[0]; val ln = nodes[--size]; val lk = keys[size]; if (size > 0) { var i = 0; while (true) { val l = i * 2 + 1; if (l >= size) break; val r = l + 1; val c = if (r < size && keys[r] < keys[l]) r else l; if (keys[c] >= lk) break; nodes[i] = nodes[c]; keys[i] = keys[c]; i = c }; nodes[i] = ln; keys[i] = lk }; return result } }
    }
    private class IntArrayList { private var a = IntArray(16); var size = 0; private set; operator fun get(i: Int) = a[i]; fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }; fun removeLast() = a[--size]; fun toArray() = a.copyOf(size) }

    fun write(file: File) {
        val up = store.upCount; val down = store.downCount
        DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION); o.writeInt(nodeCount); o.writeInt(up); o.writeInt(down)
            o.writeLong(fingerprint.size); o.writeLong(fingerprint.firstCrc); o.writeLong(fingerprint.lastCrc)
            for (v in 0 until nodeCount) o.writeInt(store.rank(v))
            for (v in 0..nodeCount) o.writeInt(store.off(v))
            for (v in 0..nodeCount) o.writeInt(store.roff(v))
            for (e in 0 until up) { o.writeInt(store.upT(e)); o.writeDouble(store.upC(e)); o.writeInt(store.upM(e)) }
            for (e in 0 until down) { o.writeInt(store.downT(e)); o.writeDouble(store.downC(e)); o.writeInt(store.downM(e)) }
        }
    }

    companion object {
        private const val MAGIC = 0x43484A31; private const val VERSION = 2
        internal fun create(n: Int, edgeN: Int, ranks: IntArray, from: IntArray, to: IntArray, cost: DoubleArray, distance: DoubleArray, middle: IntArray, childA: IntArray, childB: IntArray, fingerprint: GraphFingerprint): ContractionHierarchy {
            // Keep one minimum-cost record for each ordered pair, tie-breaking by distance then original encounter order.
            val keys = LongArray(edgeN) { e -> (from[e].toLong() shl 32) or (to[e].toLong() and 0xffffffffL) }
            val order = IntArray(edgeN) { it }
            fun sort(lo0: Int, hi0: Int) {
                var lo = lo0; var hi = hi0; val pivot = keys[order[(lo + hi) ushr 1]]; val pivotId = order[(lo + hi) ushr 1]
                while (lo <= hi) { while (keys[order[lo]] < pivot || (keys[order[lo]] == pivot && order[lo] < pivotId)) lo++; while (keys[order[hi]] > pivot || (keys[order[hi]] == pivot && order[hi] > pivotId)) hi--; if (lo <= hi) { val t = order[lo]; order[lo] = order[hi]; order[hi] = t; lo++; hi-- } }
                if (lo0 < hi) sort(lo0, hi); if (lo < hi0) sort(lo, hi0)
            }
            if (edgeN > 1) sort(0, edgeN - 1)
            val chosen = IntArray(edgeN); var unique = 0; var p = 0
            while (p < edgeN) {
                var q = p + 1; var bestEdge = order[p]
                while (q < edgeN && keys[order[q]] == keys[order[p]]) { val e = order[q]; if (cost[e] < cost[bestEdge] || (cost[e] == cost[bestEdge] && (distance[e] < distance[bestEdge] || (distance[e] == distance[bestEdge] && e < bestEdge)))) bestEdge = e; q++ }
                chosen[unique++] = bestEdge; p = q
            }
            val upCount = (0 until unique).count { val e = chosen[it]; ranks[from[e]] < ranks[to[e]] }; val downCount = unique - upCount
            val offsets = IntArray(n + 1); val reverseOffsets = IntArray(n + 1)
            for (i in 0 until unique) { val e = chosen[i]; if (ranks[from[e]] < ranks[to[e]]) offsets[from[e] + 1]++ else reverseOffsets[to[e] + 1]++ }
            for (i in 1..n) { offsets[i] += offsets[i - 1]; reverseOffsets[i] += reverseOffsets[i - 1] }
            val upT = IntArray(upCount); val upC = DoubleArray(upCount); val upM = IntArray(upCount) { -1 }; val downT = IntArray(downCount); val downC = DoubleArray(downCount); val downM = IntArray(downCount) { -1 }; val uFill = offsets.copyOf(); val dFill = reverseOffsets.copyOf()
            for (i in 0 until unique) { val e = chosen[i]; if (ranks[from[e]] < ranks[to[e]]) { val at = uFill[from[e]]++; upT[at] = to[e]; upC[at] = cost[e]; upM[at] = middle[e] } else { val at = dFill[to[e]]++; downT[at] = from[e]; downC[at] = cost[e]; downM[at] = middle[e] } }
            return ContractionHierarchy(n, ArrayChStore(ranks.copyOf(), offsets, upT, upC, upM, reverseOffsets, downT, downC, downM), fingerprint)
        }
        /** ヒープに載せず mmap で直接読む。graph 識別が一致しなければ IllegalArgumentException。 */
        fun open(file: File, graphFile: File): ContractionHierarchy = open(file, GraphFingerprint.fromFile(graphFile))
        internal fun open(file: File, expected: GraphFingerprint): ContractionHierarchy {
            val channel = FileChannel.open(file.toPath(), java.nio.file.StandardOpenOption.READ)
            try {
                val size = channel.size()
                check(size <= Int.MAX_VALUE) { "CH file too large for single mmap (>2GiB): $size" }
                check(size >= MappedChStore.HEADER_BYTES) { "Invalid CH file" }
                val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                check(buf.getInt(0) == MAGIC && buf.getInt(4) == VERSION) { "Invalid CH file" }
                val n = buf.getInt(8); val up = buf.getInt(12); val down = buf.getInt(16)
                val actual = GraphFingerprint(buf.getLong(20), buf.getLong(28), buf.getLong(36))
                require(actual == expected) { "CH graph fingerprint mismatch: CH=$actual graph=$expected" }
                require(n >= 0 && up >= 0 && down >= 0)
                val total = MappedChStore.HEADER_BYTES.toLong() + 4L * n + 8L * (n + 1) + 16L * up + 16L * down
                check(total == size) { "CH file size mismatch: expected=$total actual=$size" }
                val store = MappedChStore(buf, n, up, down)
                require(store.off(0) == 0 && store.off(n) == up && store.roff(0) == 0 && store.roff(n) == down)
                return ContractionHierarchy(n, store, actual, channel)
            } catch (t: Throwable) { channel.close(); throw t }
        }
        fun read(file: File, graphFile: File): ContractionHierarchy = read(file, GraphFingerprint.fromFile(graphFile))
        internal fun read(file: File, expected: GraphFingerprint): ContractionHierarchy {
            DataInputStream(FileInputStream(file)).use { i ->
                check(i.readInt() == MAGIC && i.readInt() == VERSION) { "Invalid CH file" }
                val n = i.readInt(); val up = i.readInt(); val down = i.readInt(); val actual = GraphFingerprint(i.readLong(), i.readLong(), i.readLong()); require(actual == expected) { "CH graph fingerprint mismatch: CH=$actual graph=$expected" }
                require(n >= 0 && up >= 0 && down >= 0)
                val rank = IntArray(n) { i.readInt() }; val offsets = IntArray(n + 1) { i.readInt() }; val reverseOffsets = IntArray(n + 1) { i.readInt() }
                require(offsets.first() == 0 && offsets.last() == up && reverseOffsets.first() == 0 && reverseOffsets.last() == down)
                val upT = IntArray(up); val upC = DoubleArray(up); val upM = IntArray(up); for (e in 0 until up) { upT[e] = i.readInt(); upC[e] = i.readDouble(); upM[e] = i.readInt() }
                val downT = IntArray(down); val downC = DoubleArray(down); val downM = IntArray(down); for (e in 0 until down) { downT[e] = i.readInt(); downC[e] = i.readDouble(); downM[e] = i.readInt() }
                return ContractionHierarchy(n, ArrayChStore(rank, offsets, upT, upC, upM, reverseOffsets, downT, downC, downM), actual)
            }
        }
    }
}
