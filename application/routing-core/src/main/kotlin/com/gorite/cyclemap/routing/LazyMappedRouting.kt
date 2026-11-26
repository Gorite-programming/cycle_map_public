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
import kotlin.math.floor

private const val DEG_LAT_METERS = 111_320.0

/** Optional app-provided sink for graph loading diagnostics. */
@Volatile
var loadTimingSink: ((String) -> Unit)? = null

private fun reportLoadStage(stage: String, startedAtNanos: Long) {
    val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
    try {
        loadTimingSink?.invoke("LOAD_STAGE name=$stage ms=$elapsedMs")
    } catch (_: Throwable) {
        // Diagnostics must never affect graph loading or routing.
    }
}

internal class MappedLongColumn private constructor(private val buffer: ByteBuffer?, private val base: Int, val size: Int, private val stride: Int, private val field: Int, private val values: LongArray?) {
    constructor(buffer: ByteBuffer, base: Int, size: Int, stride: Int, field: Int) : this(buffer, base, size, stride, field, null)
    constructor(values: LongArray) : this(null, 0, values.size, 0, 0, values)
    val indices: IntRange get() = 0 until size
    operator fun get(index: Int): Long = values?.get(index) ?: buffer!!.getLong(base + index * stride + field)
}
internal class MappedDoubleColumn private constructor(private val buffer: ByteBuffer?, private val base: Int, val size: Int, private val stride: Int, private val values: DoubleArray?, @Suppress("UNUSED_PARAMETER") marker: Unit) {
    constructor(buffer: ByteBuffer, base: Int, size: Int, stride: Int, values: DoubleArray? = null) : this(buffer, base, size, stride, values, Unit)
    constructor(values: DoubleArray) : this(null, 0, values.size, 0, values, Unit)
    val indices: IntRange get() = 0 until size
    operator fun get(index: Int): Double = values?.get(index) ?: buffer!!.getDouble(base + index * stride)
}
internal class MappedIntColumn private constructor(private val buffer: ByteBuffer?, private val base: Int, val size: Int, private val stride: Int, private val values: IntArray?) {
    constructor(buffer: ByteBuffer, base: Int, size: Int, stride: Int) : this(buffer, base, size, stride, null)
    constructor(values: IntArray) : this(null, 0, values.size, 0, values)
    operator fun get(index: Int): Int = values?.get(index) ?: buffer!!.getInt(base + index * stride)
    fun getOrNull(index: Int): Int? = if (index in 0 until size) get(index) else null
}
/** mmap 上の Short の列 (ノードごとの標高)。ヒープに写しを持たない。 */
internal class MappedShortColumn(private val buffer: ByteBuffer, private val base: Int, val size: Int) {
    fun getOrNull(index: Int): Short? = if (index in 0 until size) buffer.getShort(base + index * 2) else null
}
/** 読んだバイト数を数える (索引の末尾の区間が mmap のどこにあるかを知るため)。 */
private class CountingInputStream(input: java.io.InputStream) : java.io.FilterInputStream(input) {
    var count = 0L
        private set
    override fun read(): Int = super.read().also { if (it >= 0) count++ }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
    override fun markSupported(): Boolean = false
}

/** Sorted node IDs packed with their original indices in one Long per node. */
class SortedNodeIdIndex private constructor(private val packed: LongArray?, private val ids: LongArray?, private val order: IntArray?) {
    internal constructor(packed: LongArray, packedIds: Boolean) : this(if (packedIds) packed else null, null, null)
    internal constructor(ids: LongArray, order: IntArray) : this(null, ids, order)
    val size: Int get() = packed?.size ?: ids!!.size
    fun nodeId(index: Int): Long = packed?.let { it[index] ushr 24 } ?: ids!![index]
    fun nodeIndex(index: Int): Int = packed?.let { (it[index] and 0xFFFFFF).toInt() } ?: order!![index]
    fun binarySearch(id: Long): Int {
        var low = 0; var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = nodeId(mid)
            when { value < id -> low = mid + 1; value > id -> high = mid - 1; else -> return mid }
        }
        return -(low + 1)
    }
}

/** V1 graph reader that maps the file and decodes edge records only when routing. */
class LazyMappedRoadGraph private constructor(
    private val graphChannel: FileChannel,
    private val indexChannel: FileChannel,
    private val mapped: ByteBuffer,
    private val nodeIds: MappedLongColumn,
    private val latitudes: MappedDoubleColumn,
    private val longitudes: MappedDoubleColumn,
    private val edgeStarts: MappedLongColumn,
    private val edgeFirstOrdinals: MappedIntColumn,
    private val edgeCounts: MappedIntColumn,
    private val edgeTargets: MappedIntColumn,
    val edgeCount: Int,
) : AutoCloseable {
    /** Compatibility constructor used by in-module synthetic graph fixtures. */
    private constructor(
        graphChannel: FileChannel, mapped: ByteBuffer, nodeIds: LongArray, latitudes: DoubleArray,
        longitudes: DoubleArray, edgeStarts: LongArray, edgeFirstOrdinals: IntArray,
        edgeCounts: IntArray, edgeTargets: IntArray, edgeCount: Int,
    ) : this(graphChannel, graphChannel, mapped,
        MappedLongColumn(nodeIds), MappedDoubleColumn(latitudes), MappedDoubleColumn(longitudes),
        MappedLongColumn(edgeStarts), MappedIntColumn(edgeFirstOrdinals), MappedIntColumn(edgeCounts),
        MappedIntColumn(edgeTargets), edgeCount)

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
    internal var elevationsDecimeters: MappedShortColumn? = null

    /** 辺ごとの道路名の番号 (辺の通し番号順、無ければ -1) と、その名前の表。索引の末尾に道路名区間がある graph だけ持つ。 */
    internal var edgeRoadNameIds: MappedIntColumn? = null
    internal var roadNames: Array<String> = emptyArray()

    /** 辺ごとの車線 (turn:lanes) の番号と、その文字列の表。索引の末尾に車線区間がある graph だけ持つ。 */
    internal var edgeTurnLaneIds: MappedIntColumn? = null
    internal var turnLaneTable: Array<String> = emptyArray()

    /** Optional per-edge bicycle rules, mapped without a heap copy. */
    private var edgeBicycleInfos: MappedIntColumn? = null

    /** 名前付き交差点の索引。索引の末尾に CYCLEMAP_JUNCTIONNAME_V1 がある graph だけ持つ。 */
    internal var junctionNameIndex: JunctionNameIndex? = null

    /** 交差点名区間を持つ graph か。 */
    val hasJunctionNames: Boolean get() = junctionNameIndex != null

    /** [index] のノードから [radiusMeters] 以内にある、最も近い名前付きの交差点。区間が無い・見つからなければ null。 */
    fun junctionNameNear(index: Int, radiusMeters: Double = 35.0): JunctionName? {
        if (index !in nodeIds.indices || radiusMeters < 0.0 || radiusMeters.isNaN()) return null
        val jIndex = junctionNameIndex ?: return null
        return jIndex.findNearest(index, radiusMeters, latitudes, longitudes)
    }

    fun bicycleInfoOfEdge(ordinal: Int): BicycleRoadInfo? {
        val bits = edgeBicycleInfos?.getOrNull(ordinal) ?: return null
        return if (bits == 0) null else BicycleRoadInfo.decode(bits)
    }

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
        if (nodeCount >= 64) {
            val startedAt = System.nanoTime()
            SpatialGridIndex(latitudes, longitudes).also { reportLoadStage("spatial_grid", startedAt) }
        } else null
    }

    fun nodeAt(index: Int): GraphNode {
        require(index in nodeIds.indices) { "Unknown node index: $index" }
        return GraphNode(nodeIds[index], latitudes[index], longitudes[index])
    }

    fun nodeIndexOf(nodeId: Long): Int {
        // 以前は nodeIds.indexOf の線形探索 (広島 186 万件) で、案内の指示を作るだけで実機 5 秒以上かかっていた。
        // ID を並べ替えた表を一度だけ作り、二分探索で引く (広島で約 22MB)。
        val sorted = sortedIdIndex()
        val k = sorted.binarySearch(nodeId)
        require(k >= 0) { "Unknown node id: $nodeId" }
        return sorted.nodeIndex(k)
    }

    @Volatile private var sortedIdIndexCache: SortedNodeIdIndex? = null

    private fun sortedIdIndex(): SortedNodeIdIndex {
        sortedIdIndexCache?.let { return it }
        synchronized(this) {
            sortedIdIndexCache?.let { return it }
            val n = nodeIds.size
            val startedAt = System.nanoTime()
            val packable = n < (1 shl 24) && (0 until n).all { nodeIds[it] in 0 until (1L shl 39) }
            val result = if (packable) {
                // ID (39 ビット) と番号 (24 ビット) を 1 つの Long に詰めてプリミティブのまま並べ替える (箱詰めを避ける)
                val packed = LongArray(n) { (nodeIds[it] shl 24) or it.toLong() }
                packed.sort()
                SortedNodeIdIndex(packed, true)
            } else {
                val order = (0 until n).sortedBy { nodeIds[it] }.toIntArray()
                SortedNodeIdIndex(LongArray(n) { nodeIds[order[it]] }, order)
            }
            return result.also {
                sortedIdIndexCache = it
                reportLoadStage("sorted_node_id_index", startedAt)
            }
        }
    }

    /** ノードID昇順の (ID, 元の番号)。県間 portal 突き合わせ用。 */
    fun sortedNodeIdIndex(): SortedNodeIdIndex = sortedIdIndex()

    /** 探索ごとに一つ生成し、ノード間で再利用する辺カーソル。ノードごとのラムダ割り当てを避ける。 */
    /** [lazyTypes] なら全辺の種別表 (初回に全グラフを走査) を作らず、読んだ辺ごとに種別を引く。少数の辺だけ読む用途向け。 */
    internal inner class RoutingEdgeCursor(lazyTypes: Boolean = false) {
        private val types: ByteArray? = if (lazyTypes) null else edgeTypeCodes()
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
            val edgeBase = position
            distance = mapped.getDouble(position + 16)
            val typeLength = mapped.getShort(position + 24).toInt() and 0xffff
            val gradeFlag = position + 26 + typeLength + 1
            val hasGrade = mapped.get(gradeFlag).toInt() != 0
            grade = if (hasGrade) mapped.getDouble(gradeFlag + 1).toFloat() else Float.NaN
            position = if (hasGrade) gradeFlag + 9 else gradeFlag + 1
            target = edgeTargets[ordinal]
            type = types?.get(ordinal)?.toInt() ?: typeCodeAt(edgeBase + 26, typeLength)
            ordinal++
            return true
        }
    }

    internal fun newRoutingEdgeCursor(lazyTypes: Boolean = false): RoutingEdgeCursor = RoutingEdgeCursor(lazyTypes)

    internal fun routingEdgeMultiplier(type: Int, grade: Float, preference: RoutePreference, costMode: RouteCostMode): Double {
        val base = multiplierTable(costMode)[type]
        if (base.isNaN()) return Double.NaN
        return combinedRoutingEdgeMultiplier(base, grade, preference, costMode, 1.0)
    }

    /** Multi-source Dijkstra used by chain routing. Parent state is snapshotted before the workspace is released. */
    internal fun routeBetweenPortals(
        sources: List<PortalSource>,
        portalIndices: IntArray,
        preference: RoutePreference,
        isCancelled: (() -> Boolean)?,
        maxExpandedNodes: Int,
        costMode: RouteCostMode,
    ): ChainPortalReach {
        require(maxExpandedNodes > 0)
        val ws = acquireWorkspace()
        try {
            ws.begin()
            val pending = BooleanArray(nodeCount)
            var remaining = 0
            for (p in portalIndices) {
                require(p in 0 until nodeCount)
                if (!pending[p]) { pending[p] = true; remaining++ }
            }
            for (source in sources) {
                require(source.aIndex in 0 until nodeCount)
                val index = source.aIndex
                if (source.initialCost < ws.costOf(index)) {
                    ws.set(index, source.initialCost, source.initialDistanceMeters, -1)
                    ws.push(index, source.initialCost, source.initialCost)
                }
            }
            val edges = newRoutingEdgeCursor()
            val found = ArrayList<ChainPortalCost>()
            var expanded = 0
            var cancelled = false
            var truncated = false
            while (ws.size > 0) {
                val node = ws.topNode(); val entry = ws.topCost(); ws.pop()
                if (entry > ws.costOf(node)) continue
                expanded++
                if (isCancelled?.invoke() == true) { cancelled = true; break }
                if (pending[node]) {
                    pending[node] = false; remaining--
                    found += ChainPortalCost(node, ws.costOf(node), ws.distanceOf(node), ws.originOf(node))
                }
                if (remaining == 0) break
                if (expanded >= maxExpandedNodes) { truncated = true; break }
                edges.reset(node)
                while (edges.next()) {
                    val target = edges.target
                    if (target < 0) continue
                    val multiplier = routingEdgeMultiplier(edges.type, edges.grade, preference, costMode)
                    if (multiplier.isNaN()) continue
                    val next = ws.costOf(node) + edges.distance * multiplier
                    if (next < ws.costOf(target)) {
                        ws.set(target, next, ws.distanceOf(node) + edges.distance, node)
                        ws.push(target, next, next)
                    }
                }
            }
            val path = if (!cancelled && !truncated && portalIndices.size == 1 && found.isNotEmpty()) {
                ws.pathTo(found.single().index)
            } else null
            return ChainPortalReach(found, path, expanded, cancelled, truncated)
        } finally { releaseWorkspace(ws) }
    }

    private fun combinedRoutingEdgeMultiplier(
        baseMultiplier: Double,
        grade: Float,
        preference: RoutePreference,
        costMode: RouteCostMode,
        arterialBias: Double,
    ): Double {
        val gradePen = if (costMode == RouteCostMode.CAR) 1.0 else gradePenalty(grade, preference)
        return (baseMultiplier * gradePen * arterialBias).coerceAtLeast(BicycleCost.MIN_MULTIPLIER)
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

    /**
     * 指定半径内のノードの候補を、近い順に返す (候補があれば先頭は [nearestNodeIndex] と同じ)。
     * 半径内にノードがない場合は空配列を返す。
     * 最寄りのノードが公園内の孤立した道などに当たったとき、次の候補で経路を引き直すために使う。
     */
    fun nearestNodeCandidates(latitude: Double, longitude: Double, radiusMeters: Double = 250.0, maxCount: Int = 6): IntArray {
        if (maxCount <= 0 || radiusMeters < 0.0 || radiusMeters.isNaN()) return IntArray(0)
        val grid = spatialGrid
        val gridCandidates = grid?.findWithin(latitude, longitude, radiusMeters, maxCount, latitudes, longitudes)
        if (gridCandidates != null && gridCandidates.isEmpty()) return gridCandidates

        // グリッドの走査順と最寄り探索のリング順は、同距離のときに先頭が異なり得る。
        val first = nearestNodeIndex(latitude, longitude)
        if (first < 0) return IntArray(0)
        if (haversineMeters(latitude, longitude, latitudes[first], longitudes[first]) > radiusMeters) return IntArray(0)
        val near = if (grid != null) {
            // 探索セル数が上限を超えた場合は、従来どおり最寄りの 1 件だけを返す。
            gridCandidates ?: return intArrayOf(first)
        } else {
            // 小さなグラフ (グリッド無し) は全走査
            (0 until nodeCount)
                .map { it to haversineMeters(latitude, longitude, latitudes[it], longitudes[it]) }
                .filter { it.second <= radiusMeters }
                .sortedBy { it.second }
                .take(maxCount + 1)
                .map { it.first }
                .toIntArray()
        }
        val result = ArrayList<Int>(maxCount)
        result += first
        for (index in near) {
            if (index != first && result.size < maxCount) result += index
        }
        return result.toIntArray()
    }

    /** Returns -1 when there is no node within the requested radius. */
    fun nearestNodeIndex(latitude: Double, longitude: Double, radiusMeters: Double): Int =
        nearestNodeCandidates(latitude, longitude, radiusMeters, maxCount = 1).firstOrNull() ?: -1

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

    fun nearestNode(latitude: Double, longitude: Double): Long {
        val index = nearestNodeIndex(latitude, longitude)
        check(index >= 0) { "Graph contains no nodes" }
        return nodeIds[index]
    }

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
        val ordinal: Int = -1,
        val bicycleInfo: BicycleRoadInfo? = null,
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
                result += JunctionBranch(target, record.typeName, record.distance, record.grade, roadNameOfEdge(ordinal), turnLanesOfEdge(ordinal), ordinal, bicycleInfoOfEdge(ordinal))
            }
            ordinal++
        }
        return result
    }

    /** 通行ルール判定の候補列挙用に、ノードから出る辺を [RoadCandidate] として追加する ([bicycleInfoOfEdge] の意味は変えない)。 */
    internal fun appendRoadCandidatesFrom(
        index: Int,
        includeOrdinary: Boolean,
        out: MutableList<RoadCandidate>,
    ) {
        require(index in nodeIds.indices) { "Unknown node index: $index" }
        val fromLat = latitudes[index]
        val fromLon = longitudes[index]
        val first = edgeFirstOrdinals[index]
        val count = edgeCounts[index]
        for (i in 0 until count) {
            val ordinal = first + i
            val target = edgeTargets[ordinal]
            if (target < 0) continue
            val info = bicycleInfoOfEdge(ordinal)
            if (info == null && !includeOrdinary) continue
            out.add(RoadCandidate(ordinal, fromLat, fromLon, latitudes[target], longitudes[target], info))
        }
    }

    /** CH 問い合わせの最小インターフェース (テストで差し替え可能にするため)。 */
    internal fun interface ChQuery {
        fun route(
            start: Int,
            goal: Int,
            graph: LazyMappedRoadGraph,
            isCancelled: (() -> Boolean)?,
        ): ChRouteResult

        fun route(start: Int, goal: Int, graph: LazyMappedRoadGraph): ChRouteResult =
            route(start, goal, graph, null)
    }

    @Volatile private var chQuery: ChQuery? = null

    /** 直近の route() が使った探索エンジン ("ch" / "astar")。ログ用。 */
    @Volatile var lastEngine: String = "astar"
        private set

    /** 直近の CH 問い合わせ (経路展開まで) の所要ミリ秒。ログ用 (CH を使わなかったときは -1)。 */
    @Volatile var lastChQueryMillis: Long = -1
        private set

    /** Contraction Hierarchies を取り付ける (null で外す)。CH は 自転車×おすすめ の既定条件の問い合わせだけに使う。 */
    fun attachContractionHierarchy(ch: ContractionHierarchy?) {
        attachedHierarchy = ch
        chQuery = if (ch == null) null else ChQuery { s, g, gr, isCancelled -> ch.route(s, g, gr, isCancelled) }
    }

    /** 取り付け済みの CH 本体 (県またぎ探索の多対多で使う)。[attachContractionHierarchy] だけが設定する。 */
    @Volatile internal var attachedHierarchy: ContractionHierarchy? = null
        private set

    internal fun attachChQuery(query: ChQuery?) { chQuery = query }

    val hasContractionHierarchy: Boolean get() = chQuery != null

    /** CH を使える条件が揃い、かつ成功したときだけ結果を返す。それ以外は null (呼び出し側が A* を実行する)。 */
    private fun chRouteIfApplicable(
        start: Int, goal: Int, bounds: SearchBounds?, heuristicMultiplier: Double, arterialOnly: Boolean,
        arterialPenaltyMultiplier: Double, nonArterialPenaltyMultiplier: Double,
        isCancelled: (() -> Boolean)?, preference: RoutePreference, costMode: RouteCostMode,
    ): MappedRouteResult? {
        val query = chQuery ?: return null
        if (preference != RoutePreference.RECOMMENDED || costMode != RouteCostMode.BICYCLE || bounds != null ||
            heuristicMultiplier != 1.0 || arterialOnly || arterialPenaltyMultiplier != 1.0 || nonArterialPenaltyMultiplier != 1.0
        ) return null
        if (isCancelled?.invoke() == true) {
            lastExpandedNodes = 0
            lastEngine = "ch"
            return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, wasCancelled = true)
        }
        val queryStarted = System.nanoTime()
        val r = try {
            query.route(start, goal, this, isCancelled).also {
                lastChQueryMillis = (System.nanoTime() - queryStarted) / 1_000_000
            }
        } catch (e: Exception) {
            System.err.println("CycleMapGraph CH_QUERY_FAILED start=$start goal=$goal error=$e; falling back to A*")
            return null
        }
        lastExpandedNodes = 0
        lastEngine = "ch"
        if (r.wasCancelled) {
            return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, wasCancelled = true)
        }
        if (!r.isReachable) return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val path = r.nodeIndices
        return MappedRouteResult(
            path.map { nodeIds[it] }, r.totalDistance, r.totalCost,
            path.map { latitudes[it] to longitudes[it] },
            nodeIndices = path.toList(),
        )
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
        chRouteIfApplicable(
            start, goal, bounds, heuristicMultiplier, arterialOnly,
            arterialPenaltyMultiplier, nonArterialPenaltyMultiplier, isCancelled, preference, costMode,
        )?.let { return it }
        lastEngine = "astar"
        lastChQueryMillis = -1
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
                // キャンセル判定はラムダ呼び出しを避けて 1024 展開ごと、展開上限は軽い比較なので毎回確認する
                if ((expandedNodes and 1023) == 0 && isCancelled?.invoke() == true) {
                    lastExpandedNodes = expandedNodes
                    return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, wasCancelled = true)
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
                if (expandedNodes >= maxExpandedNodes) {
                    lastExpandedNodes = expandedNodes
                    return MappedRouteResult(emptyList(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, wasTruncated = true)
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
                    val combinedMultiplier = (multiplier * gradePen * arterialBias).coerceAtLeast(BicycleCost.MIN_MULTIPLIER)
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
        // A byte generation marker is sufficient; clear it after 255 searches.
        private val stamp = ByteArray(nodeCount)
        private var generation = 0
        private var heapNode = IntArray(1 shl 14)
        private var heapCost = DoubleArray(1 shl 14)
        private var heapKey = DoubleArray(1 shl 14)
        var size = 0
            private set

        fun begin() {
            generation++
            if (generation > 255) { stamp.fill(0); generation = 1 }
            size = 0
        }

        private fun isCurrent(i: Int): Boolean = (stamp[i].toInt() and 0xff) == generation
        fun costOf(i: Int): Double = if (isCurrent(i)) cost[i] else Double.POSITIVE_INFINITY
        fun distanceOf(i: Int): Double = distance[i]
        fun parentOf(i: Int): Int = if (isCurrent(i)) parent[i] else -1

        fun set(i: Int, c: Double, d: Double, p: Int) {
            stamp[i] = generation.toByte(); cost[i] = c; distance[i] = d; parent[i] = p
        }
        /** The root of a multi-source shortest-path tree is the initial source index. */
        fun originOf(i: Int): Int {
            if (!isCurrent(i)) return -1
            var cursor = i
            var parentIndex = parentOf(cursor)
            while (parentIndex >= 0) { cursor = parentIndex; parentIndex = parentOf(cursor) }
            return cursor
        }
        fun pathTo(i: Int): IntArray {
            val reverse = ArrayList<Int>()
            var cursor = i
            reverse += cursor
            var parentIndex = parentOf(cursor)
            while (parentIndex >= 0) {
                cursor = parentIndex
                reverse += cursor
                parentIndex = parentOf(cursor)
            }
            reverse.reverse()
            return reverse.toIntArray()
        }
        fun snapshotParents(): IntArray = IntArray(cost.size) { parentOf(it) }

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

    /** A borrowed graph may outlive a chain stage; discard its large reusable arrays afterward. */
    internal fun clearWorkspaceCache() { workspaceSlot.set(null) }

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
        haversineMeters(latitudes[from], longitudes[from], latitudes[to], longitudes[to]) * BicycleCost.MIN_MULTIPLIER * multiplier

    internal fun portalHeuristic(from: Int, to: Int): Double = heuristic(from, to, 1.0)

    private fun gradePenalty(grade: Float, preference: RoutePreference): Double {
        if (grade.isNaN()) return 1.0
        val g = grade.toDouble()
        return when (preference) {
            RoutePreference.RECOMMENDED -> BicycleCost.recommendedGradePenalty(g)
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
        // 自転車の係数は BicycleCost (CyclingCostModel と共有)。探索の内側で呼ぶので文字列ではなく種類の番号で引く。
        RouteCostMode.BICYCLE -> when (type) {
            TYPE_MOTORWAY, TYPE_MOTORWAY_LINK -> null
            TYPE_CYCLEWAY -> BicycleCost.CYCLEWAY; TYPE_TRUNK -> BicycleCost.TRUNK
            TYPE_TRUNK_LINK -> BicycleCost.TRUNK_LINK; TYPE_PRIMARY_LINK -> BicycleCost.PRIMARY_LINK
            TYPE_SECONDARY_LINK -> BicycleCost.SECONDARY_LINK; TYPE_TERTIARY_LINK -> BicycleCost.TERTIARY_LINK
            TYPE_LIVING_STREET -> BicycleCost.LIVING_STREET
            TYPE_FOOTWAY -> BicycleCost.FOOTWAY; TYPE_PEDESTRIAN -> BicycleCost.PEDESTRIAN
            TYPE_PRIMARY -> BicycleCost.PRIMARY; TYPE_SECONDARY -> BicycleCost.SECONDARY; TYPE_TERTIARY -> BicycleCost.TERTIARY
            TYPE_RESIDENTIAL -> BicycleCost.RESIDENTIAL; TYPE_UNCLASSIFIED -> BicycleCost.UNCLASSIFIED; TYPE_SERVICE -> BicycleCost.SERVICE
            TYPE_TRACK -> BicycleCost.TRACK; TYPE_PATH -> BicycleCost.PATH; else -> BicycleCost.OTHER
        }
        RouteCostMode.WALK -> when (type) {
            TYPE_MOTORWAY, TYPE_MOTORWAY_LINK -> null
            TYPE_PATH, TYPE_CYCLEWAY, TYPE_FOOTWAY, TYPE_PEDESTRIAN -> 0.90
            TYPE_RESIDENTIAL, TYPE_LIVING_STREET -> 0.95; TYPE_SERVICE -> 1.00
            TYPE_UNCLASSIFIED -> 1.05; TYPE_TERTIARY -> 1.10; TYPE_SECONDARY -> 1.20
            TYPE_PRIMARY, TYPE_PRIMARY_LINK, TYPE_TRUNK, TYPE_TRUNK_LINK -> 1.30
            TYPE_SECONDARY_LINK -> 1.20; TYPE_TERTIARY_LINK, TYPE_TRACK -> 1.10; else -> 1.00
        }
        RouteCostMode.CAR -> when (type) {
            TYPE_PATH, TYPE_TRACK, TYPE_CYCLEWAY, TYPE_FOOTWAY, TYPE_PEDESTRIAN -> null
            TYPE_MOTORWAY, TYPE_MOTORWAY_LINK, TYPE_PRIMARY, TYPE_TRUNK -> 0.90
            TYPE_TRUNK_LINK, TYPE_PRIMARY_LINK -> 0.95; TYPE_SECONDARY_LINK -> 1.00; TYPE_TERTIARY_LINK -> 1.10
            TYPE_SECONDARY -> 0.95; TYPE_TERTIARY -> 1.05
            TYPE_UNCLASSIFIED -> 1.25; TYPE_RESIDENTIAL, TYPE_LIVING_STREET -> 1.35; TYPE_SERVICE -> 1.50
            else -> 1.20
        }
    }

    private fun isArterial(type: Int): Boolean =
        type == TYPE_TRUNK || type == TYPE_PRIMARY || type == TYPE_SECONDARY || type == TYPE_TERTIARY

    override fun close() { attachedHierarchy?.close(); graphChannel.close(); indexChannel.close() }

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

        /** [readRichSections] を false にすると標高・道路名・車線の表を読まない (経路探索だけに使うグラフ向け。読み込みが速い)。 */
        fun load(file: File, indexFile: File = File(file.path + ".idx"), readRichSections: Boolean = true): LazyMappedRoadGraph {
            val totalStartedAt = System.nanoTime()
            require(indexFile.isFile) { "Missing graph index: ${indexFile.absolutePath}" }
            val channel = RandomAccessFile(file, "r").channel
            try {
                // readEdge は Int の絶対位置で読むため、2GiB 超は全体 mmap できない。不明瞭な例外ではなく明示的に失敗させる
                require(channel.size() <= Int.MAX_VALUE) {
                    "Graph file is larger than 2 GiB and cannot be mapped: ${file.absolutePath} (${channel.size()} bytes)"
                }
                var stageStartedAt = System.nanoTime()
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.BIG_ENDIAN)
                val nodeCount = readGraphHeader(mapped, file)
                val nodeBase = mapped.position()
                reportLoadStage("graph_mmap_and_header", stageStartedAt)
                val indexChannel = RandomAccessFile(indexFile, "r").channel
                try {
                    require(indexChannel.size() <= Int.MAX_VALUE) {
                        "Graph index file is larger than 2 GiB and cannot be mapped: ${indexFile.absolutePath} (${indexChannel.size()} bytes)"
                    }
                    stageStartedAt = System.nanoTime()
                    val indexMapped = indexChannel.map(FileChannel.MapMode.READ_ONLY, 0, indexChannel.size()).order(ByteOrder.BIG_ENDIAN)
                    val indexHeader = indexMapped.duplicate().order(ByteOrder.BIG_ENDIAN)
                    require(indexHeader.remaining() >= 2) { "Truncated graph index header: ${indexFile.absolutePath}" }
                    val headerLength = indexHeader.short.toInt() and 0xffff
                    require(headerLength <= indexHeader.remaining() - 4) { "Truncated graph index header: ${indexFile.absolutePath}" }
                    val header = ByteArray(headerLength); indexHeader.get(header)
                    require(String(header, Charsets.UTF_8) == "CYCLEMAP_INDEX_V2") { "Invalid graph index header: ${indexFile.absolutePath}" }
                    val indexNodeCount = indexHeader.int
                    require(indexNodeCount == nodeCount) { "Graph index node count mismatch: expected $nodeCount, got $indexNodeCount" }
                    val startsOffset = indexHeader.position()
                    val rawIndexInput = FileInputStream(indexFile)
                    rawIndexInput.channel.position(startsOffset.toLong())
                    val counting = CountingInputStream(BufferedInputStream(rawIndexInput, 1 shl 20))
                    DataInputStream(counting).use { input ->
                    val firstOffset = Math.addExact(startsOffset, Math.multiplyExact(nodeCount, 8))
                    val countsOffset = Math.addExact(firstOffset, Math.multiplyExact(nodeCount, 4))
                    val edgeCountOffset = Math.addExact(countsOffset, Math.multiplyExact(nodeCount, 4))
                    require(edgeCountOffset.toLong() + 4L <= indexMapped.limit()) { "Truncated graph index: ${indexFile.absolutePath}" }
                    val edgeCount = indexMapped.getInt(edgeCountOffset)
                    require(edgeCount >= 0) { "Invalid edge count: $edgeCount" }
                    val targetsOffset = edgeCountOffset + 4
                    require(targetsOffset.toLong() + edgeCount.toLong() * 4L <= indexMapped.limit()) { "Truncated graph targets: ${indexFile.absolutePath}" }
                    skipFully(input, 8L * nodeCount + 4L * nodeCount + 4L * nodeCount + 4L + 4L * edgeCount)
                    reportLoadStage("index_mmap_offsets_and_stream_skip", stageStartedAt)
                    stageStartedAt = System.nanoTime()
                    val latitudes = MappedDoubleColumn(mapped, nodeBase + 8, nodeCount, 24, DoubleArray(nodeCount) { mapped.getDouble(nodeBase + 8 + it * 24) })
                    val longitudes = MappedDoubleColumn(mapped, nodeBase + 16, nodeCount, 24, DoubleArray(nodeCount) { mapped.getDouble(nodeBase + 16 + it * 24) })
                    reportLoadStage("latitude_longitude_columns", stageStartedAt)
                    stageStartedAt = System.nanoTime()
                    val ids = MappedLongColumn(mapped, nodeBase, nodeCount, 24, 0)
                    reportLoadStage("ids_column_view", stageStartedAt)
                    stageStartedAt = System.nanoTime()
                    val starts = MappedLongColumn(indexMapped, startsOffset, nodeCount, 8, 0)
                    val firstOrdinals = MappedIntColumn(indexMapped, firstOffset, nodeCount, 4)
                    val counts = MappedIntColumn(indexMapped, countsOffset, nodeCount, 4)
                    reportLoadStage("starts_first_counts_column_views", stageStartedAt)
                    stageStartedAt = System.nanoTime()
                    val targets = MappedIntColumn(indexMapped, targetsOffset, edgeCount, 4)
                    reportLoadStage("targets_column_view", stageStartedAt)
                    stageStartedAt = System.nanoTime()
                    val graph = LazyMappedRoadGraph(channel, indexChannel, mapped,
                        ids,
                        latitudes,
                        longitudes,
                        starts,
                        firstOrdinals,
                        counts,
                        targets, edgeCount)
                    if (readRichSections) readTrailingSections(input, graph, nodeCount, edgeCount, indexMapped) { startsOffset + counting.count }
                    reportLoadStage("trailing_sections", stageStartedAt)
                    reportLoadStage("total", totalStartedAt)
                    return graph
                    }
                } catch (t: Throwable) {
                    indexChannel.close()
                    throw t
                }
            } catch (t: Throwable) { channel.close(); throw t }
        }

        /** Read only the fixed-width node ID column for cross-prefecture portal matching. */
        fun loadSortedNodeIdIndex(file: File): SortedNodeIdIndex {
            val totalStartedAt = System.nanoTime()
            val channel = RandomAccessFile(file, "r").channel
            try {
                require(channel.size() <= Int.MAX_VALUE) {
                    "Graph file is larger than 2 GiB and cannot be mapped: ${file.absolutePath} (${channel.size()} bytes)"
                }
                val stageStartedAt = System.nanoTime()
                val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.BIG_ENDIAN)
                val n = readGraphHeader(mapped, file)
                val base = mapped.position()
                if (n < (1 shl 24)) {
                    val packed = LongArray(n)
                    var packable = true
                    for (i in 0 until n) {
                        val id = mapped.getLong(base + i * 24)
                        if (id !in 0 until (1L shl 39)) { packable = false; break }
                        packed[i] = (id shl 24) or i.toLong()
                    }
                    if (packable) {
                    packed.sort()
                    reportLoadStage("sorted_id_index_mmap_and_build", stageStartedAt)
                    reportLoadStage("sorted_id_index_total", totalStartedAt)
                    return SortedNodeIdIndex(packed, true)
                    }
                }
                val ids = LongArray(n) { mapped.getLong(base + it * 24) }
                val order = (0 until n).sortedBy { ids[it] }.toIntArray()
                reportLoadStage("sorted_id_index_mmap_and_build", stageStartedAt)
                reportLoadStage("sorted_id_index_total", totalStartedAt)
                return SortedNodeIdIndex(LongArray(n) { ids[order[it]] }, order)
            } finally { channel.close() }
        }

        /** `.graph` ヘッダとノード数を検証して返し、バッファ位置をノード表の先頭に進める。 */
        private fun readGraphHeader(mapped: ByteBuffer, file: File): Int {
            require(mapped.remaining() >= 2) { "Truncated graph header: ${file.absolutePath}" }
            val headerLength = mapped.short.toInt() and 0xffff
            require(headerLength <= mapped.remaining() - 4) { "Truncated graph header: ${file.absolutePath}" }
            val header = ByteArray(headerLength)
            mapped.get(header)
            require(String(header, Charsets.UTF_8) == "CYCLEMAP_GRAPH_V1") { "Invalid graph header: ${file.absolutePath}" }
            val nodeCount = mapped.int
            require(nodeCount >= 0) { "Invalid node count: $nodeCount" }
            val nodeBase = mapped.position()
            require(nodeBase.toLong() + nodeCount.toLong() * 24L <= mapped.limit()) {
                "Truncated graph node table: ${file.absolutePath}"
            }
            return nodeCount
        }

        private fun skipFully(input: DataInputStream, byteCount: Long) {
            var remaining = byteCount
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) { input.readByte(); remaining-- } else remaining -= skipped
            }
        }
        const val UNKNOWN_ELEVATION: Short = Short.MIN_VALUE
        private const val ELEVATION_SECTION_MAGIC = "CYCLEMAP_ELEVATION_V1"

        private const val ROAD_NAME_SECTION_MAGIC = "CYCLEMAP_ROADNAME_V1"
        private const val TURN_LANES_SECTION_MAGIC = "CYCLEMAP_TURNLANES_V1"
        private const val BIKE_INFO_SECTION_MAGIC = "CYCLEMAP_BIKEINFO_V1"
        private const val JUNCTION_NAME_SECTION_MAGIC = "CYCLEMAP_JUNCTIONNAME_V1"

        /** 名前付き交差点の区間を読む。壊れている・途中で切れている場合は null。 */
        private fun readJunctionNameSection(
            input: DataInputStream, nodeCount: Int, indexMapped: ByteBuffer, position: () -> Long,
        ): JunctionNameIndex? {
            val nameCount = input.readInt()
            if (nameCount < 0) return null
            if (nameCount.toLong() * 2L > indexMapped.limit().toLong() - position()) return null
            val names = Array(nameCount) { input.readUTF() }

            val readingCount = input.readInt()
            if (readingCount < 0) return null
            if (readingCount.toLong() * 2L > indexMapped.limit().toLong() - position()) return null
            val readings = Array(readingCount) { input.readUTF() }

            val entryCount = input.readInt()
            if (entryCount < 0) return null
            if (entryCount.toLong() * 13L > indexMapped.limit().toLong() - position()) return null

            val nodeIndices = IntArray(entryCount)
            val nameIds = IntArray(entryCount)
            val readingIds = IntArray(entryCount)
            val kinds = IntArray(entryCount)
            for (i in 0 until entryCount) {
                val ni = input.readInt()
                val nid = input.readInt()
                val rid = input.readInt()
                val kind = input.readByte().toInt() and 0xff
                if (ni !in 0 until nodeCount || nid !in 0 until nameCount || (rid != -1 && rid !in 0 until readingCount)) {
                    return null
                }
                nodeIndices[i] = ni
                nameIds[i] = nid
                readingIds[i] = rid
                kinds[i] = kind
            }
            return JunctionNameIndex(names, readings, nodeIndices, nameIds, readingIds, kinds)
        }

        /** 文字列の表 + 辺ごとの番号 (-1 は無し) の区間を読む。件数が合わなければ null。 */
        private fun readStringTableSection(
            input: DataInputStream, edgeCount: Int, indexMapped: ByteBuffer, position: () -> Long,
        ): Pair<Array<String>, MappedIntColumn>? {
            val count = input.readInt()
            if (count < 0) return null
            // writeUTF は 1 件あたり最低 2 バイト (長さプレフィックス) を消費するため、残りバイトを超える配列確保を拒否する
            val remainingBytes = indexMapped.limit().toLong() - position()
            require(count.toLong() * 2L <= remainingBytes) {
                "String table count exceeds remaining bytes: count=$count, remaining=$remainingBytes"
            }
            val table = Array(count) { input.readUTF() }
            if (input.readInt() != edgeCount) return null
            val ids = mappedSection(indexMapped, position(), edgeCount.toLong() * 4L) ?: return null
            skipFully(input, edgeCount.toLong() * 4L)
            return table to MappedIntColumn(indexMapped, ids, edgeCount, 4)
        }

        /** 区間 ([offset] から [byteCount] バイト) が mmap に収まっていれば、その開始位置を返す。切れていれば null。 */
        private fun mappedSection(indexMapped: ByteBuffer, offset: Long, byteCount: Long): Int? =
            if (offset >= 0 && offset + byteCount <= indexMapped.limit().toLong()) offset.toInt() else null

        /**
         * 索引の末尾の追加区間 (標高・道路名) を、目印 (UTF 文字列) ごとに順に読む。旧 graph は区間が無く、
         * 知らない目印・壊れた区間・件数の不一致はそこで読むのをやめる (読めた区間だけ使う)。
         */
        private fun readTrailingSections(
            input: DataInputStream, graph: LazyMappedRoadGraph, nodeCount: Int, edgeCount: Int,
            indexMapped: ByteBuffer, position: () -> Long,
        ) {
            try {
                while (true) {
                    when (input.readUTF()) {
                        ELEVATION_SECTION_MAGIC -> {
                            if (input.readInt() != nodeCount) return
                            val base = mappedSection(indexMapped, position(), nodeCount.toLong() * 2L) ?: return
                            skipFully(input, nodeCount.toLong() * 2L)
                            graph.elevationsDecimeters = MappedShortColumn(indexMapped, base, nodeCount)
                        }
                        ROAD_NAME_SECTION_MAGIC -> {
                            val (table, ids) = readStringTableSection(input, edgeCount, indexMapped, position) ?: return
                            graph.roadNames = table
                            graph.edgeRoadNameIds = ids
                        }
                        TURN_LANES_SECTION_MAGIC -> {
                            val (table, ids) = readStringTableSection(input, edgeCount, indexMapped, position) ?: return
                            graph.turnLaneTable = table
                            graph.edgeTurnLaneIds = ids
                        }
                        BIKE_INFO_SECTION_MAGIC -> {
                            if (input.readInt() != edgeCount) return
                            val base = mappedSection(indexMapped, position(), edgeCount.toLong() * 4L) ?: return
                            skipFully(input, edgeCount.toLong() * 4L)
                            graph.edgeBicycleInfos = MappedIntColumn(indexMapped, base, edgeCount, 4)
                        }
                        JUNCTION_NAME_SECTION_MAGIC -> {
                            val jIndex = readJunctionNameSection(input, nodeCount, indexMapped, position) ?: return
                            graph.junctionNameIndex = jIndex
                        }
                        else -> return
                    }
                }
            } catch (_: java.io.IOException) {
                // 末尾に達した (EOF) か、壊れている。読めた区間だけ使う
            }
        }

        private const val TYPE_CYCLEWAY = 11; private const val TYPE_TRUNK = 12
        private const val TYPE_TRUNK_LINK = 13; private const val TYPE_PRIMARY_LINK = 14
        private const val TYPE_SECONDARY_LINK = 15; private const val TYPE_TERTIARY_LINK = 16
        private const val TYPE_LIVING_STREET = 17; private const val TYPE_FOOTWAY = 18; private const val TYPE_PEDESTRIAN = 19
        private const val TYPE_CODE_COUNT = 20
        private val TYPE_NAME_BYTES: List<Pair<Int, ByteArray>> = listOf(
            TYPE_MOTORWAY to "motorway", TYPE_MOTORWAY_LINK to "motorway_link", TYPE_PRIMARY to "primary",
            TYPE_SECONDARY to "secondary", TYPE_TERTIARY to "tertiary", TYPE_RESIDENTIAL to "residential",
            TYPE_UNCLASSIFIED to "unclassified", TYPE_SERVICE to "service", TYPE_TRACK to "track", TYPE_PATH to "path",
            TYPE_CYCLEWAY to "cycleway", TYPE_TRUNK to "trunk", TYPE_TRUNK_LINK to "trunk_link",
            TYPE_PRIMARY_LINK to "primary_link", TYPE_SECONDARY_LINK to "secondary_link", TYPE_TERTIARY_LINK to "tertiary_link",
            TYPE_LIVING_STREET to "living_street", TYPE_FOOTWAY to "footway", TYPE_PEDESTRIAN to "pedestrian",
        ).map { (code, name) -> code to name.toByteArray(Charsets.UTF_8) }
        private val MULTIPLIER_TABLES = java.util.concurrent.ConcurrentHashMap<RouteCostMode, DoubleArray>()

        private fun roadTypeCode(type: String): Int = when (type) {
            "cycleway" -> TYPE_CYCLEWAY; "trunk" -> TYPE_TRUNK; "trunk_link" -> TYPE_TRUNK_LINK
            "primary_link" -> TYPE_PRIMARY_LINK; "secondary_link" -> TYPE_SECONDARY_LINK; "tertiary_link" -> TYPE_TERTIARY_LINK
            "living_street" -> TYPE_LIVING_STREET; "footway" -> TYPE_FOOTWAY; "pedestrian" -> TYPE_PEDESTRIAN
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
    latitudes: MappedDoubleColumn,
    longitudes: MappedDoubleColumn,
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
        latitudes: MappedDoubleColumn,
        longitudes: MappedDoubleColumn,
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

    /** 指定半径内のノードを、近い順に最大 [maxCount] 個返す。探索セル数が上限を超えた場合は null。 */
    fun findWithin(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        maxCount: Int,
        latitudes: MappedDoubleColumn,
        longitudes: MappedDoubleColumn,
    ): IntArray? {
        val latDelta = radiusMeters / 110_000.0
        val maxAbsLat = maxOf(abs(latitude - latDelta), abs(latitude + latDelta)).coerceAtMost(89.0)
        val lonDelta = radiusMeters / (110_000.0 * cos(Math.toRadians(maxAbsLat)).coerceAtLeast(0.05))
        val minGy = (toGridY(latitude - latDelta) - 1).coerceAtLeast(0)
        val maxGy = (toGridY(latitude + latDelta) + 1).coerceAtMost(GRID_DIM - 1)
        val minGx = (toGridX(longitude - lonDelta) - 1).coerceAtLeast(0)
        val maxGx = (toGridX(longitude + lonDelta) + 1).coerceAtMost(GRID_DIM - 1)
        if ((maxGy - minGy + 1) * (maxGx - minGx + 1) > 4096) return null
        val found = ArrayList<Pair<Double, Int>>()
        for (gy in minGy..maxGy) {
            for (gx in minGx..maxGx) {
                var idx = cellHead[gy * GRID_DIM + gx]
                while (idx >= 0) {
                    val d = haversineMeters(latitude, longitude, latitudes[idx], longitudes[idx])
                    if (d <= radiusMeters) found += d to idx
                    idx = nextNode[idx]
                }
            }
        }
        found.sortBy { it.first }
        return IntArray(minOf(maxCount, found.size)) { found[it].second }
    }

    companion object {
        private const val GRID_DIM = 128
    }
}

/** 交差点名検索の結果。kind は 1 = 信号, 2 = junction=yes。 */
data class JunctionName(
    val name: String,
    val reading: String?,
    val kind: Int,
    val nodeIndex: Int,
    val distanceMeters: Double,
)

/**
 * 名前付き交差点ノードの空間格子インデックス。
 * 緯度経度を約 100 m 四方 (0.001 度) の格子に分け、近傍の格子だけを探査する。
 */
internal class JunctionNameIndex(
    private val names: Array<String>,
    private val readings: Array<String>,
    private val nodeIndices: IntArray,
    private val nameIds: IntArray,
    private val readingIds: IntArray,
    private val kinds: IntArray,
) {
    private class Grid(
        val cellHead: HashMap<Long, Int>,
        val nextEntry: IntArray,
    )

    @Volatile
    private var gridCache: Grid? = null

    private fun grid(latitudes: MappedDoubleColumn, longitudes: MappedDoubleColumn): Grid {
        gridCache?.let { return it }
        synchronized(this) {
            gridCache?.let { return it }
            val n = nodeIndices.size
            val cellHead = HashMap<Long, Int>(maxOf(16, n * 2))
            val nextEntry = IntArray(n) { -1 }
            for (i in 0 until n) {
                val ni = nodeIndices[i]
                val key = cellKey(toCell(latitudes[ni]), toCell(longitudes[ni]))
                val prev = cellHead.put(key, i)
                if (prev != null) nextEntry[i] = prev
            }
            return Grid(cellHead, nextEntry).also { gridCache = it }
        }
    }

    fun findNearest(
        queryIndex: Int,
        radiusMeters: Double,
        latitudes: MappedDoubleColumn,
        longitudes: MappedDoubleColumn,
    ): JunctionName? {
        if (nodeIndices.isEmpty()) return null
        val lat = latitudes[queryIndex]
        val lon = longitudes[queryIndex]
        var bestEntry = -1
        var bestDist = Double.POSITIVE_INFINITY
        var bestKindRank = Int.MAX_VALUE
        var bestIsSelf = false
        var bestNode = Int.MAX_VALUE

        fun consider(entry: Int) {
            val ni = nodeIndices[entry]
            val d = if (ni == queryIndex) 0.0 else haversineMeters(lat, lon, latitudes[ni], longitudes[ni])
            if (d > radiusMeters) return
            val kindRank = kindPriority(kinds[entry])
            val isSelf = ni == queryIndex
            val better = when {
                bestEntry < 0 -> true
                d < bestDist -> true
                d > bestDist -> false
                kindRank < bestKindRank -> true
                kindRank > bestKindRank -> false
                isSelf && !bestIsSelf -> true
                !isSelf && bestIsSelf -> false
                else -> ni < bestNode
            }
            if (better) {
                bestEntry = entry
                bestDist = d
                bestKindRank = kindRank
                bestIsSelf = isSelf
                bestNode = ni
            }
        }

        val latDelta = radiusMeters / 110_000.0
        val maxAbsLat = maxOf(abs(lat - latDelta), abs(lat + latDelta)).coerceAtMost(89.0)
        val lonDelta = radiusMeters / (110_000.0 * cos(Math.toRadians(maxAbsLat)).coerceAtLeast(0.05))
        val minCy = toCell(lat - latDelta)
        val maxCy = toCell(lat + latDelta)
        val minCx = toCell(lon - lonDelta)
        val maxCx = toCell(lon + lonDelta)
        val cellCount = (maxCy.toLong() - minCy.toLong() + 1L) * (maxCx.toLong() - minCx.toLong() + 1L)

        if (cellCount > 4096L || nodeIndices.size <= 16) {
            for (i in nodeIndices.indices) consider(i)
        } else {
            val g = grid(latitudes, longitudes)
            for (cy in minCy..maxCy) {
                for (cx in minCx..maxCx) {
                    var entry = g.cellHead[cellKey(cy, cx)] ?: continue
                    while (entry >= 0) {
                        consider(entry)
                        entry = g.nextEntry[entry]
                    }
                }
            }
        }

        if (bestEntry < 0) return null
        val rid = readingIds[bestEntry]
        return JunctionName(
            name = names[nameIds[bestEntry]],
            reading = if (rid >= 0) readings[rid] else null,
            kind = kinds[bestEntry],
            nodeIndex = nodeIndices[bestEntry],
            distanceMeters = bestDist,
        )
    }

    companion object {
        private const val CELL_DEG = 0.001
        private fun toCell(deg: Double): Int = floor(deg / CELL_DEG).toInt()
        private fun cellKey(cy: Int, cx: Int): Long = (cy.toLong() shl 32) or (cx.toLong() and 0xffffffffL)
        private fun kindPriority(kind: Int): Int = when (kind) {
            1 -> 0
            2 -> 1
            else -> 2
        }
    }
}

