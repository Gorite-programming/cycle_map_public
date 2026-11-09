package com.gorite.cyclemap.routing

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.cos

private const val DEG_LAT_METERS = 111_320.0

/** V1 graph reader that maps the file and decodes edge records only when routing. */
class LazyMappedRoadGraph private constructor(
    private val graphChannel: FileChannel,
    private val mapped: ByteBuffer,
    private val nodeIds: LongArray,
    private val latitudes: DoubleArray,
    private val longitudes: DoubleArray,
    private val edgeStarts: LongArray,
    private val edgeFirstOrdinals: IntArray,
    private val edgeCounts: IntArray,
    private val edgeTargets: IntArray,
    val edgeCount: Int,
) : AutoCloseable {
    data class SearchBounds(
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double,
    ) {
        fun contains(latitude: Double, longitude: Double): Boolean =
            latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude
    }

    val nodeCount: Int get() = nodeIds.size

    /** ノードごとの絶対標高 (0.1m 単位、不明は [UNKNOWN_ELEVATION])。索引の末尾に標高区間がある graph だけ持つ。 */
    internal var elevationsDecimeters: ShortArray? = null

    /** 辺ごとの道路名の番号 (辺の通し番号順、無ければ -1) と、その名前の表。索引の末尾に道路名区間がある graph だけ持つ。 */
    internal var edgeRoadNameIds: IntArray? = null
    internal var roadNames: Array<String> = emptyArray()

    /** 辺ごとの車線 (turn:lanes) の番号と、その文字列の表。索引の末尾に車線区間がある graph だけ持つ。 */
    internal var edgeTurnLaneIds: IntArray? = null
    internal var turnLaneTable: Array<String> = emptyArray()

    /** 辺 (通し番号 [ordinal]) の進行方向の車線 (OSM の turn:lanes)。無ければ null。 */
    fun turnLanesOfEdge(ordinal: Int): String? {
        val id = edgeTurnLaneIds?.getOrNull(ordinal) ?: return null
        return if (id < 0) null else turnLaneTable.getOrNull(id)
    }

    /** 道路名区間を持つ graph か。 */
    val hasRoadNames: Boolean get() = edgeRoadNameIds != null

    /** 辺 (通し番号 [ordinal]) の道路名 (「国道2号」「県道41号」、名前のある道はその名前)。無ければ null。 */
    fun roadNameOfEdge(ordinal: Int): String? {
        val id = edgeRoadNameIds?.getOrNull(ordinal) ?: return null
        return if (id < 0) null else roadNames.getOrNull(id)
    }

    /** 標高区間を持つ graph か。 */
    val hasElevations: Boolean get() = elevationsDecimeters != null

    /** ノードの絶対標高 (m)。標高区間が無い・不明なら null。 */
    fun elevationMetersAt(index: Int): Double? {
        val v = elevationsDecimeters?.getOrNull(index) ?: return null
        return if (v == UNKNOWN_ELEVATION) null else v / 10.0
    }

    @Volatile
    var lastExpandedNodes: Int = 0
        private set

    private val spatialGrid: SpatialGridIndex? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (nodeCount >= 64) SpatialGridIndex(latitudes, longitudes) else null
    }

    fun nodeAt(index: Int): GraphNode {
        require(index in nodeIds.indices) { "Unknown node index: $index" }
        return GraphNode(nodeIds[index], latitudes[index], longitudes[index])
    }

    fun nodeIndexOf(nodeId: Long): Int {
        // 以前は nodeIds.indexOf の線形探索 (広島 186 万件) で、案内の指示を作るだけで実機 5 秒以上かかっていた。
        // ID を並べ替えた表を一度だけ作り、二分探索で引く (広島で約 22MB)。
        val (sortedIds, order) = sortedIdIndex()
        val k = java.util.Arrays.binarySearch(sortedIds, nodeId)
        require(k >= 0) { "Unknown node id: $nodeId" }
        return order[k]
    }

    @Volatile private var sortedIdIndexCache: Pair<LongArray, IntArray>? = null

    private fun sortedIdIndex(): Pair<LongArray, IntArray> {
        sortedIdIndexCache?.let { return it }
        synchronized(this) {
            sortedIdIndexCache?.let { return it }
            val n = nodeIds.size
            val packable = n < (1 shl 24) && nodeIds.all { it in 0 until (1L shl 39) }
            val result = if (packable) {
                // ID (39 ビット) と番号 (24 ビット) を 1 つの Long に詰めてプリミティブのまま並べ替える (箱詰めを避ける)
                val packed = LongArray(n) { (nodeIds[it] shl 24) or it.toLong() }
                packed.sort()
                LongArray(n) { packed[it] ushr 24 } to IntArray(n) { (packed[it] and 0xFFFFFF).toInt() }
            } else {
                val order = (0 until n).sortedBy { nodeIds[it] }.toIntArray()
                LongArray(n) { nodeIds[order[it]] } to order
            }
            return result.also { sortedIdIndexCache = it }
        }
    }

    /** ノードID昇順の (ID, 元の番号)。県間 portal 突き合わせ用。 */
    internal fun sortedNodeIdIndex(): Pair<LongArray, IntArray> = sortedIdIndex()

    /** 探索ごとに一つ生成し、ノード間で再利用する辺カーソル。ノードごとのラムダ割り当てを避ける。 */
    internal inner class RoutingEdgeCursor {
        private val types = edgeTypeCodes()
        private var position = 0
        private var ordinal = 0
        private var end = 0
        var target: Int = -1; private set
        var distance: Double = 0.0; private set
        var type: Int = 0; private set
        var grade: Float = Float.NaN; private set

        fun reset(from: Int) {
            require(from in nodeIds.indices)
            position = edgeStarts[from].toInt()
            ordinal = edgeFirstOrdinals[from]
            end = ordinal + edgeCounts[from]
        }

        fun next(): Boolean {
            if (ordinal >= end) return false
            distance = mapped.getDouble(position + 16)
            val typeLength = mapped.getShort(position + 24).toInt() and 0xffff
            val gradeFlag = position + 26 + typeLength + 1
            val hasGrade = mapped.get(gradeFlag).toInt() != 0
            grade = if (hasGrade) mapped.getDouble(gradeFlag + 1).toFloat() else Float.NaN
            position = if (hasGrade) gradeFlag + 9 else gradeFlag + 1
            target = edgeTargets[ordinal]
            type = types[ordinal].toInt()
            ordinal++
            return true
        }
    }

    internal fun newRoutingEdgeCursor(): RoutingEdgeCursor = RoutingEdgeCursor()

    internal fun routingEdgeMultiplier(type: Int, grade: Float, preference: RoutePreference, costMode: RouteCostMode): Double {
        val base = multiplierTable(costMode)[type]
        if (base.isNaN()) return Double.NaN
        return combinedRoutingEdgeMultiplier(base, grade, preference, costMode, 1.0)
    }

    private fun combinedRoutingEdgeMultiplier(
        baseMultiplier: Double,
        grade: Float,
        preference: RoutePreference,
        costMode: RouteCostMode,
        arterialBias: Double,
    ): Double {
        val gradePen = if (costMode == RouteCostMode.CAR) 1.0 else gradePenalty(grade, preference)
        return (baseMultiplier * gradePen * arterialBias).coerceAtLeast(0.90)
    }

    fun nearestNodeIndex(latitude: Double, longitude: Double): Int {
        if (nodeCount == 0) return -1
        val grid = spatialGrid
        if (grid != null) {
            val found = grid.findNearest(latitude, longitude, latitudes, longitudes)
            if (found >= 0) return found
        }
        return nearestNodeIndexLinear(latitude, longitude)
    }

    internal fun nearestNodeIndexLinear(latitude: Double, longitude: Double): Int {
        if (nodeCount == 0) return -1
        // 2パス探索: haversineは三角関数4回で重いため、1パス目で安価な
        // 等距円筒近似の最良点を求め、2パス目では近似距離が暫定最良を
        // 下回る候補にだけhaversineを計算する。結果は全走査と同一。
        val cosLat = cos(Math.toRadians(latitude))
        var seed = 0
        var seedApprox = Double.POSITIVE_INFINITY
        for (i in nodeIds.indices) {
            val approx = approxDistSq(latitude, longitude, latitudes[i], longitudes[i], cosLat)
            if (approx < seedApprox) { seedApprox = approx; seed = i }
        }
        var best = seed
        var bestDistance = haversineMeters(latitude, longitude, latitudes[seed], longitudes[seed])
        var bestDistSq = bestDistance * bestDistance
        for (i in nodeIds.indices) {
            if (i == seed) continue
            // 近似はhaversineの下限ではないが、暫定最良より明らかに遠い点の枝刈りには使える。
            // 境界付近の取りこぼしを避けるため2倍のマージンを取る。
            val approx = approxDistSq(latitude, longitude, latitudes[i], longitudes[i], cosLat)
            if (approx > bestDistSq * 4.0) continue
            val distance = haversineMeters(latitude, longitude, latitudes[i], longitudes[i])
            if (distance < bestDistance) {
                bestDistance = distance
                bestDistSq = distance * distance
                best = i
            }
        }
        return best
    }

    private fun approxDistSq(lat: Double, lon: Double, lat2: Double, lon2: Double, cosLat: Double): Double {
        val dLat = (lat2 - lat) * DEG_LAT_METERS
        val dLon = (lon2 - lon) * DEG_LAT_METERS * cosLat
        return dLat * dLat + dLon * dLon
    }

    fun nearestNode(latitude: Double, longitude: Double): Long = nodeIds[nearestNodeIndex(latitude, longitude)]

    /**
     * 案内生成用の読み取り専用ビュー (①基本経路指示)。
     * ルーティング探索には一切影響しない。呼び出し側で graphLock.read 保持下で使うこと。
     */
    data class JunctionBranch(
        val targetIndex: Int,
        val roadTypeName: String,
        val distanceMeters: Double,
        val gradePercent: Float = Float.NaN,
        /** 道路名 (graph が道路名区間を持つときだけ)。 */
        val roadName: String? = null,
        /** この辺の進行方向の車線 (OSM の turn:lanes)。graph が車線区間を持つときだけ。 */
        val turnLanes: String? = null,
    )

    fun outgoingBranchCount(index: Int): Int {
        require(index in nodeIds.indices) { "Unknown node index: $index" }
        return edgeCounts[index]
    }

    fun outgoingBranches(index: Int): List<JunctionBranch> {
        require(index in nodeIds.indices) { "Unknown node index: $index" }
        val result = ArrayList<JunctionBranch>(edgeCounts[index])
        var position = edgeStarts[index]
        var ordinal = edgeFirstOrdinals[index]
        repeat(edgeCounts[index]) {
            val record = readEdge(position)
            position = record.nextOffset
            val target = edgeTargets[ordinal]
            if (target >= 0) {
                result += JunctionBranch(target, record.typeName, record.distance, record.grade, roadNameOfEdge(ordinal), turnLanesOfEdge(ordinal))
            }
            ordinal++
        }
        return result
    }

    fun route(
        start: Int,
        goal: Int,
        bounds: SearchBounds? = null,
        heuristicMultiplier: Double = 1.0,
        arterialOnly: Boolean = false,
        arterialPenaltyMultiplier: Double = 1.0,
        nonArterialPenaltyMultiplier: Double = 1.0,
        isCancelled: (() -> Boolean)? = null,
        maxExpandedNodes: Int = Int.MAX_VALUE,
        preference: RoutePreference = RoutePreference.RECOMMENDED,
        costMode: RouteCostMode = RouteCostMode.BICYCLE,
    ): MappedRouteResult {
        require(start in nodeIds.indices && goal in nodeIds.indices) { "Unknown node index" }
        require(heuristicMultiplier >= 1.0) { "heuristicMultiplier must be at least 1.0" }
        require(arterialPenaltyMultiplier >= 1.0) { "arterialPenaltyMultiplier must be at least 1.0" }
        require(nonArterialPenaltyMultiplier >= 1.0) { "nonArterialPenaltyMultiplier must be at least 1.0" }
        require(maxExpandedNodes > 0) { "maxExpandedNodes must be positive" }
        if (start == goal) {
            lastExpandedNodes = 0
            return MappedRouteResult(listOf(nodeIds[start]), 0.0, 0.0, listOf(latitudes[start] to longitudes[start]), nodeIndices = listOf(start))
        }
        val types = edgeTypeCodes()
        val multipliers = multiplierTable(costMode)
        val ws = acquireWorkspace()
        try {
            ws.begin()
            var expandedNodes = 0
            ws.set(start, 0.0, 0.0, -1)
            ws.push(start, 0.0, heuristic(start, goal, heuristicMultiplier))
            while (ws.size > 0) {
                val current = ws.topNode()
                val entryCost = ws.topCost()
                ws.pop()
                if (entryCost > ws.costOf(current)) continue
                expandedNodes++
                // 1024展開ごとにキャンセル・上限を判定 (毎回の判定コストを避ける)
                if ((expandedNodes and 1023) == 0) {
                    if (isCancelled?.invoke() == true) {
                        lastExpandedNodes = expandedNodes
                        return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, wasCancelled = true)
                    }
                    if (expandedNodes >= maxExpandedNodes) {
                        lastExpandedNodes = expandedNodes
                        return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, wasTruncated = true)
                    }
                }
                if (current == goal) {
                    lastExpandedNodes = expandedNodes
                    val path = ArrayList<Int>(); var cursor = goal; path += cursor
                    while (ws.parentOf(cursor) >= 0) { cursor = ws.parentOf(cursor); path += cursor }
                    path.reverse()
                    return MappedRouteResult(
                        path.map { nodeIds[it] }, ws.distanceOf(goal), ws.costOf(goal),
                        path.map { latitudes[it] to longitudes[it] },
                        nodeIndices = path,
                    )
                }
                if (bounds != null && current != start &&
                    !bounds.contains(latitudes[current], longitudes[current])
                ) {
                    continue
                }
                val currentCost = ws.costOf(current)
                val currentDistance = ws.distanceOf(current)
                // 辺を割り当てなしで読む (レコード: from 8 / to 8 / 距離 8 / 種別 UTF / 一方通行 1 / 勾配あり 1 / 勾配 8)
                var p = edgeStarts[current].toInt()
                var ordinal = edgeFirstOrdinals[current]
                val end = ordinal + edgeCounts[current]
                while (ordinal < end) {
                    val edgeDistance = mapped.getDouble(p + 16)
                    val typeLength = mapped.getShort(p + 24).toInt() and 0xffff
                    val gradeFlag = p + 26 + typeLength + 1
                    val hasGrade = mapped.get(gradeFlag).toInt() != 0
                    val grade = if (hasGrade) mapped.getDouble(gradeFlag + 1).toFloat() else Float.NaN
                    p = if (hasGrade) gradeFlag + 9 else gradeFlag + 1
                    val type = types[ordinal].toInt()
                    val target = edgeTargets[ordinal]
                    ordinal++
                    val multiplier = multipliers[type]
                    if (multiplier.isNaN()) continue
                    val arterial = isArterial(type)
                    if (arterialOnly && !arterial && current != start && target != goal) continue
                    if (target < 0) continue
                    if (target != goal && bounds != null && !bounds.contains(latitudes[target], longitudes[target])) continue
                    val arterialBias = if (arterial) arterialPenaltyMultiplier else nonArterialPenaltyMultiplier
                    val gradePen = if (costMode == RouteCostMode.CAR) 1.0 else gradePenalty(grade, preference)
                    val combinedMultiplier = (multiplier * gradePen * arterialBias).coerceAtLeast(0.90)
                    val newCost = currentCost + edgeDistance * combinedMultiplier
                    if (newCost < ws.costOf(target)) {
                        ws.set(target, newCost, currentDistance + edgeDistance, current)
                        ws.push(target, newCost, newCost + heuristic(target, goal, heuristicMultiplier))
                    }
                }
            }
            lastExpandedNodes = expandedNodes
            return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        } finally {
            releaseWorkspace(ws)
        }
    }

    /** ノード [from] から出る辺の行き先のうち、座標がちょうど ([lat], [lon]) のもの。無ければ -1。 */
    internal fun neighborAt(from: Int, lat: Double, lon: Double): Int {
        val first = edgeFirstOrdinals[from]
        for (ordinal in first until first + edgeCounts[from]) {
            val t = edgeTargets[ordinal]
            if (t >= 0 && latitudes[t] == lat && longitudes[t] == lon) return t
        }
        return -1
    }

    /**
     * ノード [from] から [to] への辺の (距離, 勾配%) 。複数あれば最短の辺。無ければ null。勾配が無い辺は NaN。
     * outgoingBranches と違い、文字列やオブジェクトを辺ごとに作らない。
     */
    fun edgeDistanceAndGrade(from: Int, to: Int): Pair<Double, Float>? {
        var p = edgeStarts[from].toInt()
        var ordinal = edgeFirstOrdinals[from]
        val end = ordinal + edgeCounts[from]
        var bestDistance = Double.POSITIVE_INFINITY
        var bestGrade = Float.NaN
        while (ordinal < end) {
            val d = mapped.getDouble(p + 16)
            val typeLength = mapped.getShort(p + 24).toInt() and 0xffff
            val gradeFlag = p + 26 + typeLength + 1
            val hasGrade = mapped.get(gradeFlag).toInt() != 0
            val grade = if (hasGrade) mapped.getDouble(gradeFlag + 1).toFloat() else Float.NaN
            p = if (hasGrade) gradeFlag + 9 else gradeFlag + 1
            if (edgeTargets[ordinal] == to && d < bestDistance) { bestDistance = d; bestGrade = grade }
            ordinal++
        }
        return if (bestDistance.isInfinite()) null else bestDistance to bestGrade
    }

    // ---- 探索の高速化のための補助 (結果は従来の実装と同じ。探索ごとの割り当てを無くす) ----

    /** 辺 (通し番号) ごとの道路種別コード。最初の探索の前に graph を 1 度なめて作る (広島で約 4MB)。 */
    @Volatile private var edgeTypeCodesCache: ByteArray? = null

    private fun edgeTypeCodes(): ByteArray {
        edgeTypeCodesCache?.let { return it }
        synchronized(this) {
            edgeTypeCodesCache?.let { return it }
            val codes = ByteArray(edgeCount)
            for (node in nodeIds.indices) {
                var p = edgeStarts[node].toInt()
                var ordinal = edgeFirstOrdinals[node]
                repeat(edgeCounts[node]) {
                    val typeLength = mapped.getShort(p + 24).toInt() and 0xffff
                    codes[ordinal] = typeCodeAt(p + 26, typeLength).toByte()
                    val gradeFlag = p + 26 + typeLength + 1
                    p = if (mapped.get(gradeFlag).toInt() != 0) gradeFlag + 9 else gradeFlag + 1
                    ordinal++
                }
            }
            edgeTypeCodesCache = codes
            return codes
        }
    }

    /** mmap 上の UTF の道路種別を、文字列を作らずに種別コードにする。 */
    private fun typeCodeAt(offset: Int, length: Int): Int {
        for ((code, bytes) in TYPE_NAME_BYTES) {
            if (bytes.size != length) continue
            var same = true
            for (k in 0 until length) if (mapped.get(offset + k) != bytes[k]) { same = false; break }
            if (same) return code
        }
        return 0
    }

    /** 移動手段ごとの、種別コード → コスト倍率の表 (通れない種別は NaN)。 */
    private fun multiplierTable(costMode: RouteCostMode): DoubleArray = MULTIPLIER_TABLES.getOrPut(costMode) {
        DoubleArray(TYPE_CODE_COUNT) { type -> multiplier(type, costMode) ?: Double.NaN }
    }

    /**
     * 探索の作業領域 (ノードごとのコスト・距離・親、世代番号、二分ヒープ)。graph ごとに 1 つを使い回し、
     * 同時に複数の探索が走るときだけ追加で作る。世代番号で「この探索で触れたか」を判定するので、毎回の初期化は不要。
     */
    private class SearchWorkspace(nodeCount: Int) {
        private val cost = DoubleArray(nodeCount)
        private val distance = DoubleArray(nodeCount)
        private val parent = IntArray(nodeCount)
        private val stamp = IntArray(nodeCount)
        private var generation = 0
        private var heapNode = IntArray(1 shl 14)
        private var heapCost = DoubleArray(1 shl 14)
        private var heapKey = DoubleArray(1 shl 14)
        var size = 0
            private set

        fun begin() {
            generation++
            if (generation == Int.MAX_VALUE) { stamp.fill(0); generation = 1 }
            size = 0
        }

        fun costOf(i: Int): Double = if (stamp[i] == generation) cost[i] else Double.POSITIVE_INFINITY
        fun distanceOf(i: Int): Double = distance[i]
        fun parentOf(i: Int): Int = if (stamp[i] == generation) parent[i] else -1

        fun set(i: Int, c: Double, d: Double, p: Int) {
            stamp[i] = generation; cost[i] = c; distance[i] = d; parent[i] = p
        }

        fun topNode(): Int = heapNode[0]
        fun topCost(): Double = heapCost[0]

        fun push(node: Int, c: Double, key: Double) {
            if (size == heapNode.size) {
                heapNode = heapNode.copyOf(size * 2); heapCost = heapCost.copyOf(size * 2); heapKey = heapKey.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val parentSlot = (i - 1) ushr 1
                if (heapKey[parentSlot] <= key) break
                heapNode[i] = heapNode[parentSlot]; heapCost[i] = heapCost[parentSlot]; heapKey[i] = heapKey[parentSlot]
                i = parentSlot
            }
            heapNode[i] = node; heapCost[i] = c; heapKey[i] = key
        }

        fun pop() {
            size--
            if (size == 0) return
            val node = heapNode[size]; val c = heapCost[size]; val key = heapKey[size]
            var i = 0
            while (true) {
                var child = 2 * i + 1
                if (child >= size) break
                if (child + 1 < size && heapKey[child + 1] < heapKey[child]) child++
                if (heapKey[child] >= key) break
                heapNode[i] = heapNode[child]; heapCost[i] = heapCost[child]; heapKey[i] = heapKey[child]
                i = child
            }
            heapNode[i] = node; heapCost[i] = c; heapKey[i] = key
        }
    }

    private val workspaceSlot = java.util.concurrent.atomic.AtomicReference<SearchWorkspace?>(null)

    private fun acquireWorkspace(): SearchWorkspace = workspaceSlot.getAndSet(null) ?: SearchWorkspace(nodeIds.size)

    private fun releaseWorkspace(ws: SearchWorkspace) { workspaceSlot.set(ws) }

    private data class EdgeRecord(
        val to: Long,
        val distance: Double,
        val type: Int,
        val typeName: String,
        val grade: Float,
        val nextOffset: Long,
    )

    private fun readEdge(offset: Long): EdgeRecord {
        // All position arithmetic stays in Long to handle files > 2 GB correctly.
        // ByteBuffer absolute-get methods accept Int indices; the cast is safe as long as
        // the mapped file fits in Int range, which is guaranteed by mmap (max 2 GB on
        // most JVMs). For files that could exceed that limit this would need chunked mapping,
        // but we keep the cast explicit and validated here.
        val p = offset
        val pi = p.toInt()
        val to = mapped.getLong(pi + 8)
        val distance = mapped.getDouble(pi + 16)
        val typeStartL = p + 24
        val typeStartI = typeStartL.toInt()
        val length = mapped.getShort(typeStartI).toInt() and 0xffff
        // 絶対位置のgetのみ使う: 共有バッファのpositionを変えず、辺ごとのduplicate()割り当ても避ける
        val road = ByteArray(length)
        for (i in 0 until length) road[i] = mapped.get(typeStartI + 2 + i)
        val typeName = String(road, Charsets.UTF_8)
        val type = roadTypeCode(typeName)
        val onewayFlagOffset = typeStartL + 2 + length      // points at the oneway byte
        val hasGradeFlagOffset = onewayFlagOffset + 1        // points at the hasGrade byte
        val gradeDataOffset = hasGradeFlagOffset + 1         // points at grade double (if present)
        val grade = if (mapped.get(hasGradeFlagOffset.toInt()).toInt() != 0) {
            mapped.getDouble(gradeDataOffset.toInt()).toFloat()
        } else Float.NaN
        val nextOffset = if (grade.isNaN()) gradeDataOffset else gradeDataOffset + 8
        return EdgeRecord(to, distance, type, typeName, grade, nextOffset)
    }

    private fun heuristic(from: Int, to: Int, multiplier: Double) =
        haversineMeters(latitudes[from], longitudes[from], latitudes[to], longitudes[to]) * 0.90 * multiplier

    internal fun portalHeuristic(from: Int, to: Int): Double = heuristic(from, to, 1.0)

    private fun gradePenalty(grade: Float, preference: RoutePreference): Double {
        if (grade.isNaN()) return 1.0
        val g = grade.toDouble()
        return when (preference) {
            RoutePreference.RECOMMENDED -> when {
                g >= 0.0 -> 1.0 + (g * 0.02) + (g * g * 0.002) // 緩やかな上りは+2%、急勾配は二次関数的に増加
                g >= -4.0 -> (1.0 + g * 0.02).coerceAtLeast(0.95) // 緩やかな下りは軽快（最大-5%）
                else -> 0.95 + abs(g + 4.0) * 0.03 // -4%を超える急坂下りは減速ペナルティ（-4%で0.95から滑らかに接続）
            }
            RoutePreference.FLAT -> when {
                // 上り坂を強く回避（上限8.0倍でクランプし探索空間の球状化を防止）。
                // 係数は広島の実グラフ (OD 147 組) で測って決めた: 旧 (0.10, 0.01, 上限5) は登り -9.5% / 距離 +2.0%、
                // 現行 (0.20, 0.02, 上限8) は登り -16.0% / 距離 +4.5%、さらに強い (0.35, 0.04, 上限12) は登り -18.7% / 距離 +6.9% で割に合わない。
                // 合成係数は呼び出し側で 0.90 以上に切り上げるので、A* のヒューリスティック下限は変わらない。
                g > 0.0 -> (1.0 + g * 0.20 + g * g * 0.02).coerceAtMost(8.0)
                g >= -5.0 -> (1.0 + g * 0.01).coerceAtLeast(0.95)
                else -> (0.95 + abs(g + 5.0) * 0.04).coerceAtMost(3.0)
            }
            RoutePreference.HILL_CLIMB -> when {
                // 上り坂を優先（ペナルティなし）
                g > 0.0 -> 1.0
                // 平坦および下り坂に相対ペナルティ（0%で1.0から下り勾配に応じて滑らかに増加、上限2.0倍）
                else -> (1.0 + abs(g) * 0.05).coerceAtMost(2.0)
            }
        }
    }

    private fun multiplier(type: Int, costMode: RouteCostMode = RouteCostMode.BICYCLE): Double? = when (costMode) {
        RouteCostMode.BICYCLE -> when (type) {
            TYPE_MOTORWAY, TYPE_MOTORWAY_LINK -> null
            TYPE_PRIMARY -> 0.90; TYPE_SECONDARY -> 0.95; TYPE_TERTIARY -> 1.00
            TYPE_RESIDENTIAL -> 1.20; TYPE_UNCLASSIFIED -> 1.25; TYPE_SERVICE -> 1.35
            TYPE_TRACK -> 1.60; TYPE_PATH -> 1.80; else -> 1.15
        }
        RouteCostMode.WALK -> when (type) {
            TYPE_MOTORWAY, TYPE_MOTORWAY_LINK -> null
            TYPE_PATH -> 0.90; TYPE_RESIDENTIAL -> 0.95; TYPE_SERVICE -> 1.00
            TYPE_UNCLASSIFIED -> 1.05; TYPE_TERTIARY -> 1.10; TYPE_SECONDARY -> 1.20
            TYPE_PRIMARY -> 1.30; TYPE_TRACK -> 1.10; else -> 1.00
        }
        RouteCostMode.CAR -> when (type) {
            TYPE_PATH, TYPE_TRACK -> null
            TYPE_MOTORWAY, TYPE_MOTORWAY_LINK, TYPE_PRIMARY -> 0.90
            TYPE_SECONDARY -> 0.95; TYPE_TERTIARY -> 1.05
            TYPE_UNCLASSIFIED -> 1.25; TYPE_RESIDENTIAL -> 1.35; TYPE_SERVICE -> 1.50
            else -> 1.20
        }
    }

    private fun isArterial(type: Int): Boolean =
        type == TYPE_PRIMARY || type == TYPE_SECONDARY || type == TYPE_TERTIARY

    override fun close() = graphChannel.close()

    companion object {
        /**
         * 起点・終点を含む探索bboxを余裕付きで作る。長距離の誤爆的迂回を切らないよう
         * マージンは直線距離の [marginFraction] + 最低 [minMarginMeters] と generous に取る。
         * 正当な迂回がbbox外に出る可能性は残るため、到達不能時はbboxなしで再試行すること。
         */
        fun searchBoundsFor(
            startLat: Double,
            startLon: Double,
            goalLat: Double,
            goalLon: Double,
            marginFraction: Double = 0.5,
            minMarginMeters: Double = 10_000.0,
        ): SearchBounds {
            val straight = haversineMeters(startLat, startLon, goalLat, goalLon)
            val margin = maxOf(straight * marginFraction, minMarginMeters)
            val midLat = Math.toRadians((startLat + goalLat) / 2.0)
            val latMarginDeg = margin / DEG_LAT_METERS
            val lonMarginDeg = margin / (DEG_LAT_METERS * cos(midLat).coerceAtLeast(0.2))
            return SearchBounds(
                minLatitude = minOf(startLat, goalLat) - latMarginDeg,
                maxLatitude = maxOf(startLat, goalLat) + latMarginDeg,
                minLongitude = minOf(startLon, goalLon) - lonMarginDeg,
                maxLongitude = maxOf(startLon, goalLon) + lonMarginDeg,
            )
        }
        private const val TYPE_MOTORWAY = 1; private const val TYPE_MOTORWAY_LINK = 2
        private const val TYPE_PRIMARY = 3; private const val TYPE_SECONDARY = 4; private const val TYPE_TERTIARY = 5
        private const val TYPE_RESIDENTIAL = 6; private const val TYPE_UNCLASSIFIED = 7; private const val TYPE_SERVICE = 8
        private const val TYPE_TRACK = 9; private const val TYPE_PATH = 10

        fun load(file: File, indexFile: File = File(file.path + ".idx")): LazyMappedRoadGraph {
            require(indexFile.isFile) { "Missing graph index: ${indexFile.absolutePath}" }
            val channel = RandomAccessFile(file, "r").channel
            try {
                // readEdge は Int の絶対位置で読むため、2GiB 超は全体 mmap できない。不明瞭な例外ではなく明示的に失敗させる
                require(channel.size() <= Int.MAX_VALUE) {
                    "Graph file is larger than 2 GiB and cannot be mapped: ${file.absolutePath} (${channel.size()} bytes)"
                }
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.BIG_ENDIAN)
                val headerLength = mapped.short.toInt() and 0xffff
                val header = ByteArray(headerLength); mapped.get(header)
                check(String(header, Charsets.UTF_8) == "CYCLEMAP_GRAPH_V1")
                val nodeCount = mapped.int
                val ids = LongArray(nodeCount); val lats = DoubleArray(nodeCount); val lons = DoubleArray(nodeCount)
                repeat(nodeCount) { i -> ids[i] = mapped.long; lats[i] = mapped.double; lons[i] = mapped.double }
                DataInputStream(BufferedInputStream(FileInputStream(indexFile))).use { input ->
                    check(input.readUTF() == "CYCLEMAP_INDEX_V2")
                    check(input.readInt() == nodeCount)
                    val starts = LongArray(nodeCount) { input.readLong() }
                    val first = IntArray(nodeCount) { input.readInt() }
                    val counts = IntArray(nodeCount) { input.readInt() }
                    val edgeCount = input.readInt()
                    val targets = IntArray(edgeCount) { input.readInt() }
                    val graph = LazyMappedRoadGraph(channel, mapped, ids, lats, lons, starts, first, counts, targets, edgeCount)
                    readTrailingSections(input, graph, nodeCount, edgeCount)
                    return graph
                }
            } catch (t: Throwable) { channel.close(); throw t }
        }
        const val UNKNOWN_ELEVATION: Short = Short.MIN_VALUE
        private const val ELEVATION_SECTION_MAGIC = "CYCLEMAP_ELEVATION_V1"

        private const val ROAD_NAME_SECTION_MAGIC = "CYCLEMAP_ROADNAME_V1"
        private const val TURN_LANES_SECTION_MAGIC = "CYCLEMAP_TURNLANES_V1"

        /** 文字列の表 + 辺ごとの番号 (-1 は無し) の区間を読む。件数が合わなければ null。 */
        private fun readStringTableSection(input: DataInputStream, edgeCount: Int): Pair<Array<String>, IntArray>? {
            val count = input.readInt()
            if (count < 0) return null
            val table = Array(count) { input.readUTF() }
            if (input.readInt() != edgeCount) return null
            return table to IntArray(edgeCount) { input.readInt() }
        }

        /**
         * 索引の末尾の追加区間 (標高・道路名) を、目印 (UTF 文字列) ごとに順に読む。旧 graph は区間が無く、
         * 知らない目印・壊れた区間・件数の不一致はそこで読むのをやめる (読めた区間だけ使う)。
         */
        private fun readTrailingSections(input: DataInputStream, graph: LazyMappedRoadGraph, nodeCount: Int, edgeCount: Int) {
            try {
                while (true) {
                    when (input.readUTF()) {
                        ELEVATION_SECTION_MAGIC -> {
                            if (input.readInt() != nodeCount) return
                            graph.elevationsDecimeters = ShortArray(nodeCount) { input.readShort() }
                        }
                        ROAD_NAME_SECTION_MAGIC -> {
                            val (table, ids) = readStringTableSection(input, edgeCount) ?: return
                            graph.roadNames = table
                            graph.edgeRoadNameIds = ids
                        }
                        TURN_LANES_SECTION_MAGIC -> {
                            val (table, ids) = readStringTableSection(input, edgeCount) ?: return
                            graph.turnLaneTable = table
                            graph.edgeTurnLaneIds = ids
                        }
                        else -> return
                    }
                }
            } catch (_: java.io.IOException) {
                // 末尾に達した (EOF) か、壊れている。読めた区間だけ使う
            }
        }

        private const val TYPE_CODE_COUNT = 11
        private val TYPE_NAME_BYTES: List<Pair<Int, ByteArray>> = listOf(
            TYPE_MOTORWAY to "motorway", TYPE_MOTORWAY_LINK to "motorway_link", TYPE_PRIMARY to "primary",
            TYPE_SECONDARY to "secondary", TYPE_TERTIARY to "tertiary", TYPE_RESIDENTIAL to "residential",
            TYPE_UNCLASSIFIED to "unclassified", TYPE_SERVICE to "service", TYPE_TRACK to "track", TYPE_PATH to "path",
        ).map { (code, name) -> code to name.toByteArray(Charsets.UTF_8) }
        private val MULTIPLIER_TABLES = java.util.concurrent.ConcurrentHashMap<RouteCostMode, DoubleArray>()

        private fun roadTypeCode(type: String): Int = when (type) {
            "motorway" -> TYPE_MOTORWAY; "motorway_link" -> TYPE_MOTORWAY_LINK; "primary" -> TYPE_PRIMARY
            "secondary" -> TYPE_SECONDARY; "tertiary" -> TYPE_TERTIARY; "residential" -> TYPE_RESIDENTIAL
            "unclassified" -> TYPE_UNCLASSIFIED; "service" -> TYPE_SERVICE; "track" -> TYPE_TRACK; "path" -> TYPE_PATH
            else -> 0
        }
    }
}

/** ルート探索の移動手段モード (A* ヒューリスティック下限 0.90 を維持)。 */
enum class RouteCostMode {
    BICYCLE,
    WALK,
    CAR,
}

/** ルート探索の優先方針 (平坦優先・坂道優先など)。 */
enum class RoutePreference(val label: String) {
    RECOMMENDED("おすすめ"),
    FLAT("平坦優先"),
    HILL_CLIMB("坂道優先"),
}

/**
 * プリミティブ配列ベースの軽量2次元空間グリッドインデックス。
 * 全走査と100%同一の最近傍ノード結果を保証しつつ、探索対象セルを絞り込む。
 */
internal class SpatialGridIndex(
    latitudes: DoubleArray,
    longitudes: DoubleArray,
) {
    private val minLat: Double
    private val maxLat: Double
    private val minLon: Double
    private val maxLon: Double
    private val latSpan: Double
    private val lonSpan: Double
    private val cellHead = IntArray(GRID_DIM * GRID_DIM) { -1 }
    private val nextNode = IntArray(latitudes.size) { -1 }

    init {
        var mnLat = Double.POSITIVE_INFINITY
        var mxLat = Double.NEGATIVE_INFINITY
        var mnLon = Double.POSITIVE_INFINITY
        var mxLon = Double.NEGATIVE_INFINITY
        for (i in latitudes.indices) {
            val lat = latitudes[i]
            val lon = longitudes[i]
            if (lat < mnLat) mnLat = lat
            if (lat > mxLat) mxLat = lat
            if (lon < mnLon) mnLon = lon
            if (lon > mxLon) mxLon = lon
        }
        minLat = mnLat
        maxLat = mxLat
        minLon = mnLon
        maxLon = mxLon
        latSpan = (maxLat - minLat).coerceAtLeast(1e-6)
        lonSpan = (maxLon - minLon).coerceAtLeast(1e-6)

        for (i in latitudes.indices) {
            val gy = toGridY(latitudes[i])
            val gx = toGridX(longitudes[i])
            val cell = gy * GRID_DIM + gx
            nextNode[i] = cellHead[cell]
            cellHead[cell] = i
        }
    }

    private fun toGridY(lat: Double): Int =
        (((lat - minLat) / latSpan) * (GRID_DIM - 1)).toInt().coerceIn(0, GRID_DIM - 1)

    private fun toGridX(lon: Double): Int =
        (((lon - minLon) / lonSpan) * (GRID_DIM - 1)).toInt().coerceIn(0, GRID_DIM - 1)

    fun findNearest(
        latitude: Double,
        longitude: Double,
        latitudes: DoubleArray,
        longitudes: DoubleArray,
    ): Int {
        val centerGy = toGridY(latitude)
        val centerGx = toGridX(longitude)
        var bestNode = -1
        var bestDist = Double.POSITIVE_INFINITY

        // 1. 中心セルから同心リング状に広げて初期候補点 (seed) を取得
        for (radius in 0..16) {
            val rMinY = (centerGy - radius).coerceAtLeast(0)
            val rMaxY = (centerGy + radius).coerceAtMost(GRID_DIM - 1)
            val rMinX = (centerGx - radius).coerceAtLeast(0)
            val rMaxX = (centerGx + radius).coerceAtMost(GRID_DIM - 1)
            for (gy in rMinY..rMaxY) {
                for (gx in rMinX..rMaxX) {
                    if (radius > 0 && gy > centerGy - radius && gy < centerGy + radius &&
                        gx > centerGx - radius && gx < centerGx + radius
                    ) {
                        continue
                    }
                    var idx = cellHead[gy * GRID_DIM + gx]
                    while (idx >= 0) {
                        val d = haversineMeters(latitude, longitude, latitudes[idx], longitudes[idx])
                        if (d < bestDist) {
                            bestDist = d
                            bestNode = idx
                        }
                        idx = nextNode[idx]
                    }
                }
            }
            if (bestNode >= 0) break
        }
        if (bestNode < 0) return -1

        // 2. 暫定最短距離 bestDist (m) を包含する厳密な緯度経度バウンディングボックスを算出
        val latDelta = (bestDist * 1.02) / 110_000.0
        val maxAbsLat = maxOf(abs(latitude - latDelta), abs(latitude + latDelta)).coerceAtMost(89.0)
        val lonDelta = (bestDist * 1.02) / (110_000.0 * cos(Math.toRadians(maxAbsLat)).coerceAtLeast(0.05))

        val minGy = (toGridY(latitude - latDelta) - 1).coerceAtLeast(0)
        val maxGy = (toGridY(latitude + latDelta) + 1).coerceAtMost(GRID_DIM - 1)
        val minGx = (toGridX(longitude - lonDelta) - 1).coerceAtLeast(0)
        val maxGx = (toGridX(longitude + lonDelta) + 1).coerceAtMost(GRID_DIM - 1)

        if ((maxGy - minGy + 1) * (maxGx - minGx + 1) > 4096) {
            return -1
        }

        for (gy in minGy..maxGy) {
            for (gx in minGx..maxGx) {
                var idx = cellHead[gy * GRID_DIM + gx]
                while (idx >= 0) {
                    val d = haversineMeters(latitude, longitude, latitudes[idx], longitudes[idx])
                    if (d < bestDist) {
                        bestDist = d
                        bestNode = idx
                    }
                    idx = nextNode[idx]
                }
            }
        }
        return bestNode
    }

    companion object {
        private const val GRID_DIM = 128
    }
}

