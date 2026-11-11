package com.gorite.cyclemap.routing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.PriorityQueue
import kotlin.math.max

/** S1 offline contraction hierarchy builder. Edge storage is primitive and grows geometrically. */
class ContractionHierarchyBuilder(
    private val maxWitnessSettled: Int = DEFAULT_MAX_WITNESS_SETTLED,
    private val maxWitnessCost: Double = DEFAULT_MAX_WITNESS_COST,
) {
    init { require(maxWitnessSettled > 0 && maxWitnessCost > 0.0) }

    data class BuildResult(val hierarchy: ContractionHierarchy, val shortcutCount: Int, val inputEdgeCount: Int, val elapsedMillis: Long)

    fun build(graph: LazyMappedRoadGraph): BuildResult {
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
        while (queue.isNotEmpty()) {
            val item = queue.remove(); val v = item.node
            if (!active[v]) continue
            val p = priority(v)
            if (kotlin.math.abs(p - item.priority) > 1e-9) { queue += QueueNode(v, p, item.version + 1); continue }
            // Collect active incoming and outgoing edges without allocating edge objects.
            val ins = IntArrayList(); var e = headIn[v]
            while (e >= 0) { if (active[from[e]]) ins.add(e); e = nextIn[e] }
            val outs = IntArrayList(); e = headOut[v]
            while (e >= 0) { if (active[to[e]]) outs.add(e); e = nextOut[e] }
            // Do not publish shortcuts until every witness search for v is done.
            // A shortcut created for an earlier incoming edge may itself pass
            // through v; exposing it immediately would let later witness searches
            // incorrectly use a path that the excluded node is supposed to avoid.
            val pendingFrom = IntArrayList(); val pendingTo = IntArrayList()
            val pendingCost = DoubleArrayList(); val pendingDistance = DoubleArrayList()
            val pendingChildA = IntArrayList(); val pendingChildB = IntArrayList()
            for (i in 0 until ins.size) {
                val ie = ins[i]; val u = from[ie]
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
        val hierarchy = ContractionHierarchy.create(n, edgeN, rank, from, to, cost, dist, middle, childA, childB)
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
    }
}

data class ChRouteResult(val nodeIndices: IntArray, val totalCost: Double, val totalDistance: Double) {
    val isReachable get() = nodeIndices.isNotEmpty()
}

/** S1 CSR hierarchy. Every stored arc is directed from lower to higher rank. */
class ContractionHierarchy private constructor(
    val nodeCount: Int, val edgeCount: Int, private val rank: IntArray,
    private val offsets: IntArray, private val targets: IntArray, private val costs: DoubleArray,
    private val distances: DoubleArray, private val middles: IntArray,
    private val reverseOffsets: IntArray, private val reverseEdges: IntArray, private val reverseTargets: IntArray, private val reverseCosts: DoubleArray,
    private val queryToAll: IntArray, private val allFrom: IntArray, private val allTargets: IntArray, private val allCosts: DoubleArray, private val allDistances: DoubleArray,
    private val allChildrenA: IntArray, private val allChildrenB: IntArray,
) {
    private val ws = QueryWorkspace(nodeCount)

    @Synchronized fun route(start: Int, goal: Int): ChRouteResult {
        require(start in 0 until nodeCount && goal in 0 until nodeCount)
        if (start == goal) return ChRouteResult(intArrayOf(start), 0.0, 0.0)
        ws.begin(); ws.setF(start, 0.0, -1); ws.setB(goal, 0.0, -1); ws.pushF(start, 0.0); ws.pushB(goal, 0.0)
        var best = Double.POSITIVE_INFINITY; var meet = -1
        while (true) {
            // The forward upward graph and backward reversed-downward graph are
            // different search spaces. The ordinary bidirectional sum bound can
            // stop before the other side reaches a valid CH peak, so retire each
            // side only when its own lower bound cannot improve the best route.
            val expandF = ws.hasF() && ws.minF() < best
            val expandB = ws.hasB() && ws.minB() < best
            if (!expandF && !expandB) break
            if (expandF && (!expandB || ws.minF() <= ws.minB())) {
                val (v, d) = ws.popF(); if (d > ws.fCost(v)) continue
                if (d + ws.bCost(v) < best) { best = d + ws.bCost(v); meet = v }
                for (e in offsets[v] until offsets[v + 1]) { val w = targets[e]; val nd = d + costs[e]; if (nd < ws.fCost(w)) { ws.setF(w, nd, e); ws.pushF(w, nd); if (nd + ws.bCost(w) < best) { best = nd + ws.bCost(w); meet = w } } }
            } else {
                val (v, d) = ws.popB(); if (d > ws.bCost(v)) continue
                if (d + ws.fCost(v) < best) { best = d + ws.fCost(v); meet = v }
                for (i in reverseOffsets[v] until reverseOffsets[v + 1]) { val e = i; val w = reverseTargets[e]; val nd = d + reverseCosts[e]; if (nd < ws.bCost(w)) { ws.setB(w, nd, e); ws.pushB(w, nd); if (nd + ws.fCost(w) < best) { best = nd + ws.fCost(w); meet = w } } }
            }
        }
        if (meet < 0 || best.isInfinite()) return ChRouteResult(IntArray(0), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val arcList = IntArrayList()
        var v = meet
        var guard = 0
        while (v != start) { check(++guard <= nodeCount) { "Forward parent cycle at node $v" }; val e = ws.fEdge(v); if (e < 0) return ChRouteResult(IntArray(0), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY); arcList.add(queryToAll[e]); v = edgeSource(e) }
        val path = IntArrayList(); path.add(start)
        for (i in arcList.size - 1 downTo 0) expand(arcList[i], path)
        v = meet
        guard = 0
        while (v != goal) { check(++guard <= nodeCount) { "Backward parent cycle at node $v" }; val q = ws.bEdge(v); if (q < 0) return ChRouteResult(IntArray(0), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY); val e = reverseEdges[q]; expand(e, path); v = allTargets[e] }
        var totalDist = 0.0
        for (i in 0 until arcList.size) totalDist += allDistances[arcList[i]]
        v = meet; while (v != goal) { val e = ws.bEdge(v); totalDist += allDistances[reverseEdges[e]]; v = allTargets[reverseEdges[e]] }
        return ChRouteResult(path.toArray(), best, totalDist)
    }

    private fun edgeSource(edge: Int): Int { // lower endpoint is recoverable from CSR offset through binary search
        var lo = 0; var hi = nodeCount
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (offsets[mid + 1] <= edge) lo = mid + 1 else hi = mid }
        return lo
    }
    private fun expand(edge: Int, out: IntArrayList) {
        // Explicit expansion stack; push right then left to preserve traversal order.
        val stack = IntArrayList(); stack.add(edge)
        var steps = 0
        while (stack.size > 0) {
            check(++steps < 50_000_000) { "Shortcut expansion exceeded limit; edge=$edge, stack=${stack.size}" }
            val e = stack.removeLast(); val a = allChildrenA[e]
            if (a < 0) out.add(allTargets[e]) else { val b = allChildrenB[e]; check(a < e && b < e) { "Invalid shortcut decomposition edge=$e children=$a,$b" }; stack.add(b); stack.add(a) }
        }
    }

    private class QueryWorkspace(n: Int) {
        private val genF = IntArray(n); private val genB = IntArray(n); private var generation = 0
        private val cf = DoubleArray(n); private val cb = DoubleArray(n); private val pf = IntArray(n); private val pb = IntArray(n)
        private val qf = MinHeap(); private val qb = MinHeap()
        fun begin() { generation++; if (generation == 0) { genF.fill(0); genB.fill(0); generation = 1 }; qf.clear(); qb.clear() }
        fun setF(n: Int, d: Double, e: Int) { genF[n] = generation; cf[n] = d; pf[n] = e }
        fun setB(n: Int, d: Double, e: Int) { genB[n] = generation; cb[n] = d; pb[n] = e }
        fun fCost(n: Int) = if (genF[n] == generation) cf[n] else Double.POSITIVE_INFINITY
        fun bCost(n: Int) = if (genB[n] == generation) cb[n] else Double.POSITIVE_INFINITY
        fun fEdge(n: Int) = if (genF[n] == generation) pf[n] else -1
        fun bEdge(n: Int) = if (genB[n] == generation) pb[n] else -1
        fun pushF(n: Int, d: Double) { qf.add(n, d) }; fun pushB(n: Int, d: Double) { qb.add(n, d) }
        fun hasF() = !qf.isEmpty; fun hasB() = !qb.isEmpty
        fun minF() = qf.minCost; fun minB() = qb.minCost
        fun popF() = qf.remove().let { it to qf.removedCost }; fun popB() = qb.remove().let { it to qb.removedCost }
        private class MinHeap {
            private var nodes = IntArray(32); private var keys = DoubleArray(32); private var size = 0
            var removedCost = 0.0; private set
            val isEmpty get() = size == 0
            val minCost get() = if (size == 0) Double.POSITIVE_INFINITY else keys[0]
            fun clear() { size = 0 }
            fun add(node: Int, key: Double) {
                if (size == nodes.size) { nodes = nodes.copyOf(nodes.size * 2); keys = keys.copyOf(keys.size * 2) }
                var i = size++; while (i > 0) { val p = (i - 1) ushr 1; if (keys[p] <= key) break; nodes[i] = nodes[p]; keys[i] = keys[p]; i = p }
                nodes[i] = node; keys[i] = key
            }
            fun remove(): Int {
                check(size > 0); val result = nodes[0]; removedCost = keys[0]
                val lastN = nodes[--size]; val lastK = keys[size]
                if (size > 0) { var i = 0; while (true) { val l = i * 2 + 1; if (l >= size) break; val r = l + 1; val c = if (r < size && keys[r] < keys[l]) r else l; if (keys[c] >= lastK) break; nodes[i] = nodes[c]; keys[i] = keys[c]; i = c }; nodes[i] = lastN; keys[i] = lastK }
                return result
            }
        }
    }
    private class IntArrayList { private var a = IntArray(16); var size = 0; private set; operator fun get(i: Int) = a[i]; fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }; fun removeLast() = a[--size]; fun toArray() = a.copyOf(size) }

    fun write(file: File) {
        DataOutputStream(FileOutputStream(file)).use { o ->
            o.writeInt(MAGIC); o.writeInt(VERSION); o.writeInt(nodeCount); o.writeInt(edgeCount); o.writeInt(allTargets.size); o.writeInt(reverseEdges.size)
            rank.forEach(o::writeInt); offsets.forEach(o::writeInt); reverseOffsets.forEach(o::writeInt)
            queryToAll.forEach(o::writeInt)
            reverseEdges.forEach(o::writeInt)
            for (e in allTargets.indices) { o.writeInt(allFrom[e]); o.writeInt(allTargets[e]); o.writeDouble(allCosts[e]); o.writeDouble(allDistances[e]); o.writeInt(allChildrenA[e]); o.writeInt(allChildrenB[e]) }
        }
    }

    companion object {
        private const val MAGIC = 0x43484A31; private const val VERSION = 1
        internal fun create(n: Int, edgeN: Int, ranks: IntArray, from: IntArray, to: IntArray, cost: DoubleArray, distance: DoubleArray, middle: IntArray, childA: IntArray, childB: IntArray): ContractionHierarchy {
            var count = 0; for (e in 0 until edgeN) if (ranks[from[e]] < ranks[to[e]]) count++
            val offsets = IntArray(n + 1); for (e in 0 until edgeN) if (ranks[from[e]] < ranks[to[e]]) offsets[from[e] + 1]++
            for (i in 1..n) offsets[i] += offsets[i - 1]
            val targets = IntArray(count); val costs = DoubleArray(count); val dists = DoubleArray(count); val mids = IntArray(count); val queryMap = IntArray(count); val fill = offsets.copyOf()
            for (e in 0 until edgeN) if (ranks[from[e]] < ranks[to[e]]) { val p = fill[from[e]]++; targets[p] = to[e]; costs[p] = cost[e]; dists[p] = distance[e]; mids[p] = middle[e]; queryMap[p] = e }
            val downCount = (0 until edgeN).count { ranks[from[it]] > ranks[to[it]] }
            val revOffsets = IntArray(n + 1); for (e in 0 until edgeN) if (ranks[from[e]] > ranks[to[e]]) revOffsets[to[e] + 1]++
            for (i in 1..n) revOffsets[i] += revOffsets[i - 1]
            val rev = IntArray(downCount); val revTargets = IntArray(downCount); val revCosts = DoubleArray(downCount); val rfill = revOffsets.copyOf()
            for (e in 0 until edgeN) if (ranks[from[e]] > ranks[to[e]]) { val p = rfill[to[e]]++; rev[p] = e; revTargets[p] = from[e]; revCosts[p] = cost[e] }
            return ContractionHierarchy(n, count, ranks, offsets, targets, costs, dists, mids, revOffsets, rev, revTargets, revCosts, queryMap, from.copyOf(edgeN), to.copyOf(edgeN), cost.copyOf(edgeN), distance.copyOf(edgeN), childA.copyOf(edgeN), childB.copyOf(edgeN))
        }
        fun read(file: File): ContractionHierarchy {
            DataInputStream(FileInputStream(file)).use { i ->
                check(i.readInt() == MAGIC && i.readInt() == VERSION) { "Invalid CH file" }
                val n = i.readInt(); val m = i.readInt(); val allN = i.readInt(); val downN = i.readInt(); require(n >= 0 && m >= 0 && allN >= m && downN >= 0)
                val rank = IntArray(n) { i.readInt() }; val offsets = IntArray(n + 1) { i.readInt() }; val revOffsets = IntArray(n + 1) { i.readInt() }
                require(offsets.first() == 0 && offsets.last() == m && revOffsets.first() == 0 && revOffsets.last() == downN)
                val queryMap = IntArray(m) { i.readInt() }; val reverse = IntArray(downN) { i.readInt() }
                val allFrom = IntArray(allN); val allTargets = IntArray(allN); val allCosts = DoubleArray(allN); val allDistances = DoubleArray(allN); val childA = IntArray(allN); val childB = IntArray(allN)
                for (e in 0 until allN) { allFrom[e] = i.readInt(); allTargets[e] = i.readInt(); allCosts[e] = i.readDouble(); allDistances[e] = i.readDouble(); childA[e] = i.readInt(); childB[e] = i.readInt() }
                val targets = IntArray(m); val costs = DoubleArray(m); val dists = DoubleArray(m); val mids = IntArray(m)
                for (e in 0 until m) { val a = queryMap[e]; require(a in 0 until allN); targets[e] = allTargets[a]; costs[e] = allCosts[a]; dists[e] = allDistances[a]; mids[e] = if (childA[a] < 0) -1 else allTargets[childA[a]] }
                val reverseTargets = IntArray(downN); val reverseCosts = DoubleArray(downN)
                for (e in 0 until downN) { val a = reverse[e]; require(a in 0 until allN); reverseTargets[e] = allFrom[a]; reverseCosts[e] = allCosts[a] }
                return ContractionHierarchy(n, m, rank, offsets, targets, costs, dists, mids, revOffsets, reverse, reverseTargets, reverseCosts, queryMap, allFrom, allTargets, allCosts, allDistances, childA, childB)
            }
        }
    }
}
