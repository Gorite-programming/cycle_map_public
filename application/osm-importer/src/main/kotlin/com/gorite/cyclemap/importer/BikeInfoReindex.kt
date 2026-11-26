package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.BicycleRoadInfo
import com.gorite.cyclemap.routing.BikeAccess
import com.gorite.cyclemap.routing.ExcludedRoadIndex
import com.gorite.cyclemap.routing.ExcludedSegment
import com.gorite.cyclemap.routing.SideFacility
import com.gorite.cyclemap.routing.classifyBicycleRoad
import crosby.binary.osmosis.OsmosisReader
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer
import org.openstreetmap.osmosis.core.domain.v0_6.Way
import org.openstreetmap.osmosis.core.domain.v0_6.Node
import org.openstreetmap.osmosis.core.task.v0_6.Sink
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

internal const val BIKEINFO_MAGIC = "CYCLEMAP_BIKEINFO_V1"

/** Only graph pairs are retained. No boxed pair/edge objects or PBF-wide way table.
 * Arrays use about 50 bytes/edge including the hash table (well below 4 GiB for millions of edges).
 */
internal class BikeInfoReindex(
    val from: LongArray,
    val to: LongArray,
    val highwayIds: IntArray,
    val highways: List<String>,
    val nodeCount: Int = 0,
    private val ref: String? = null,
    val bounds: ExcludedRoadBounds? = null,
) {
    val size get() = from.size
    val values = IntArray(size)
    private val owners = LongArray(size)
    // 0 unmatched, 1 unique way, 2 ambiguous. Zero is also a valid encoded classification.
    internal val states = ByteArray(size)
    internal val tagMasks = ByteArray(size)
    private val duplicates = IntArray(size) { -1 }
    private val slots: IntArray

    init {
        require(to.size == size && highwayIds.size == size)
        var capacity = 1
        while (capacity.toLong() < size.toLong() * 2) {
            require(capacity < (1 shl 29)) { "Too many edges" }
            capacity *= 2
        }
        slots = IntArray(capacity) { -1 }
        for (i in from.indices) {
            val slot = slot(from[i], to[i])
            duplicates[i] = slots[slot]
            slots[slot] = i
        }
    }

    private fun slot(a: Long, b: Long): Int {
        var h = a * -7046029254386353131L + b
        h = (h xor (h ushr 33)) * -49064778989728563L
        var s = (h xor (h ushr 32)).toInt() and (slots.size - 1)
        while (slots[s] >= 0 && (from[slots[s]] != a || to[slots[s]] != b)) s = (s + 1) and (slots.size - 1)
        return s
    }

    fun accept(way: OsmWay) {
        if (!BicycleAccessFilter.isAllowed(way.tags)) return
        val forward = classifyBicycleRoad(way.tags, false).encode()
        val backward = classifyBicycleRoad(way.tags, true).encode()
        var mask = 0
        for ((bit, prefix) in listOf(1 to "sidewalk", 2 to "cycleway", 4 to "bicycle")) {
            if (way.tags.keys.any { it == prefix || it.startsWith("$prefix:") }) mask = mask or bit
        }
        if (ref != null && way.tags["ref"]?.split(';')?.any { it.trim() == ref } == true) mask = mask or 8
        for (i in 0 until way.nodeIds.size - 1) {
            match(way.nodeIds[i], way.nodeIds[i + 1], way.id, forward, mask)
            match(way.nodeIds[i + 1], way.nodeIds[i], way.id, backward, mask)
        }
    }

    private fun match(a: Long, b: Long, wayId: Long, bits: Int, mask: Int) {
        var i = slots[slot(a, b)]
        while (i >= 0) {
            tagMasks[i] = (tagMasks[i].toInt() or mask).toByte()
            if (states[i].toInt() == 0) {
                states[i] = 1; owners[i] = wayId; values[i] = bits
            } else if (owners[i] != wayId || values[i] != bits) {
                // A repeated pair within one way is harmless unless its directions disagree.
                states[i] = 2; values[i] = 0
            }
            i = duplicates[i]
        }
    }

    fun statistics(refOnly: Boolean = false, highway: Int? = null): BikeInfoStats {
        val stats = BikeInfoStats()
        for (i in from.indices) {
            if (refOnly && tagMasks[i].toInt() and 8 == 0) continue
            if (highway != null && highwayIds[i] != highway) continue
            stats.add(states[i].toInt(), values[i], tagMasks[i].toInt())
        }
        return stats
    }

    fun printStatistics(out: PrintStream = System.out) {
        fun report(refOnly: Boolean) {
            out.println(if (refOnly) "ref=$ref (ambiguous edges included if any candidate way has this ref)" else "all edges")
            out.print(statistics(refOnly).format())
            highways.forEachIndexed { id, name ->
                val stats = statistics(refOnly, id)
                if (stats.total > 0) { out.println("highway=$name"); out.print(stats.format()) }
            }
        }
        report(false)
        if (ref != null) report(true)
        val stats = statistics()
        if ((stats.unmatched + stats.ambiguous) * 10L >= size && size > 0) {
            out.println("WARNING: at least 10% of edges could not be matched unambiguously to PBF ways")
        }
    }

    companion object {
        fun readGraph(file: File, ref: String? = null): BikeInfoReindex =
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readUTF() == "CYCLEMAP_GRAPH_V1") { "Unsupported graph" }
                val nodes = input.readInt(); require(nodes >= 0)
                var minLat = Double.POSITIVE_INFINITY; var minLon = Double.POSITIVE_INFINITY
                var maxLat = Double.NEGATIVE_INFINITY; var maxLon = Double.NEGATIVE_INFINITY
                repeat(nodes) {
                    input.readLong()
                    val lat = input.readDouble(); val lon = input.readDouble()
                    minLat = minOf(minLat, lat); maxLat = maxOf(maxLat, lat)
                    minLon = minOf(minLon, lon); maxLon = maxOf(maxLon, lon)
                }
                val count = input.readInt(); require(count >= 0)
                val from = LongArray(count); val to = LongArray(count); val types = IntArray(count)
                val names = linkedMapOf<String, Int>()
                // Exactly writeGraphIndex's repeat(edgeCount) order: never group or sort edges.
                repeat(count) { ordinal ->
                    from[ordinal] = input.readLong(); to[ordinal] = input.readLong()
                    input.readDouble()
                    val name = input.readUTF()
                    types[ordinal] = names.getOrPut(name) { names.size }
                    input.readBoolean()
                    if (input.readBoolean()) input.readDouble()
                }
                val bounds = if (nodes == 0) null else ExcludedRoadBounds(minLat - 0.01, minLon - 0.01, maxLat + 0.01, maxLon + 0.01)
                BikeInfoReindex(from, to, types, names.keys.toList(), nodes, ref, bounds)
            }
    }
}

internal class BikeInfoStats {
    var total = 0L
    var unique = 0L
    var ambiguous = 0L
    var unmatched = 0L
    val roadway = LongArray(BikeAccess.entries.size)
    val left = LongArray(SideFacility.entries.size)
    val right = LongArray(SideFacility.entries.size)
    val flags = LongArray(14)
    val tags = LongArray(3)

    fun add(state: Int, bits: Int, mask: Int) {
        total++
        when (state) {
            0 -> unmatched++
            2 -> ambiguous++
            else -> {
                unique++
                // Classify unique matches even when encode() is zero; the reader reserves zero for null.
                val info = BicycleRoadInfo.decode(bits)
                roadway[info.roadway.ordinal]++
                left[info.left.facility.ordinal]++; right[info.right.facility.ordinal]++
                flags.indices.forEach { if (info.flags and (1 shl it) != 0) flags[it]++ }
                tags.indices.forEach { if (mask and (1 shl it) != 0) tags[it]++ }
            }
        }
    }

    fun format(): String = buildString {
        fun line(label: String, count: Long) {
            appendLine("$label=$count (${String.format(Locale.ROOT, "%.2f", if (total == 0L) 0.0 else 100.0 * count / total)}%)")
        }
        appendLine("edges=$total; percentages use this edge count; rules/tags count unique matches only")
        line("unique", unique); line("ambiguous", ambiguous); line("unmatched", unmatched)
        BikeAccess.entries.forEach { line("roadway.$it", roadway[it.ordinal]) }
        SideFacility.entries.forEach { line("left.$it", left[it.ordinal]); line("right.$it", right[it.ordinal]) }
        val flagNames = listOf("ONEWAY", "CONTRAFLOW_ALLOWED", "USE_SIDEPATH", "SEPARATE_CYCLEWAY", "SEPARATE_SIDEWALK",
            "SEGREGATED", "MOTORROAD", "CONDITIONAL_TAGS", "SIDEWALK_LEFT", "SIDEWALK_RIGHT", "PEDESTRIAN_SHARED")
        flags.indices.forEach { line("flag.${flagNames.getOrNull(it) ?: "RESERVED_$it"}", flags[it]) }
        listOf("sidewalk", "cycleway", "bicycle").forEachIndexed { i, tag -> line("tag.$tag", tags[i]) }
    }
}

/**
 * Parse section boundaries rather than scanning raw bytes (a road name can contain the magic).
 * Returns the byte offset where [BIKEINFO_MAGIC] should be inserted: immediately before
 * [JUNCTION_NAME_SECTION_MAGIC] if present, or at EOF otherwise.
 */
internal fun validateBikeInfoInput(index: File, nodes: Int, edges: Int): Long {
    RandomAccessFile(index, "r").use { input ->
        fun skip(bytes: Long) {
            require(bytes >= 0 && bytes <= input.length() - input.filePointer) { "Truncated index" }
            input.seek(input.filePointer + bytes)
        }
        require(input.readUTF() == "CYCLEMAP_INDEX_V2") { "Unsupported index" }
        require(input.readInt() == nodes) { "Index node count differs from graph" }
        skip(nodes * 16L)
        require(input.readInt() == edges) { "Index edge count differs from graph" }
        skip(edges * 4L)
        var junctionSectionStart: Long? = null
        while (input.filePointer < input.length()) {
            val sectionStart = input.filePointer
            when (val magic = input.readUTF()) {
                BIKEINFO_MAGIC -> error("Input index already contains $BIKEINFO_MAGIC")
                "CYCLEMAP_ELEVATION_V1" -> {
                    require(input.readInt() == nodes); skip(nodes * 2L)
                }
                "CYCLEMAP_ROADNAME_V1", "CYCLEMAP_TURNLANES_V1" -> {
                    val count = input.readInt(); require(count >= 0)
                    repeat(count) { input.readUTF() }
                    require(input.readInt() == edges); skip(edges * 4L)
                }
                JUNCTION_NAME_SECTION_MAGIC -> {
                    require(junctionSectionStart == null) { "Duplicate $JUNCTION_NAME_SECTION_MAGIC section" }
                    junctionSectionStart = sectionStart
                    val nameCount = input.readInt(); require(nameCount >= 0)
                    repeat(nameCount) { input.readUTF() }
                    val readingCount = input.readInt(); require(readingCount >= 0)
                    repeat(readingCount) { input.readUTF() }
                    val entryCount = input.readInt(); require(entryCount >= 0)
                    skip(entryCount.toLong() * 13L)
                }
                else -> error("Unknown index section: $magic")
            }
        }
        return junctionSectionStart ?: input.length()
    }
}

private fun copyExactBytes(src: InputStream, dst: OutputStream, byteCount: Long) {
    val buf = ByteArray(8192)
    var remaining = byteCount
    while (remaining > 0) {
        val read = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (read < 0) throw EOFException("Unexpected EOF while copying index")
        dst.write(buf, 0, read)
        remaining -= read
    }
}

internal fun writeBikeInfoIndex(input: File, output: File, nodes: Int, values: IntArray) {
    val insertOffset = validateBikeInfoInput(input, nodes, values.size)
    // CREATE_NEW also prevents overwriting inputs through symlinks/hard links or a mistaken output path.
    val stream = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    try {
        DataOutputStream(BufferedOutputStream(stream)).use { out ->
            input.inputStream().buffered().use { src ->
                copyExactBytes(src, out, insertOffset)
                out.writeUTF(BIKEINFO_MAGIC); out.writeInt(values.size)
                values.forEach(out::writeInt)
                src.copyTo(out)
            }
        }
    } catch (failure: Throwable) {
        try { Files.deleteIfExists(output.toPath()) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}

/** graph のノード範囲（余白込み）と線分の交差を調べる。境界上も含む。 */
internal data class ExcludedRoadBounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun intersects(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean {
        var start = 0.0; var end = 1.0
        fun axis(from: Double, to: Double, low: Double, high: Double): Boolean {
            val delta = to - from
            if (delta == 0.0) return from in low..high
            val t0 = (low - from) / delta; val t1 = (high - from) / delta
            start = maxOf(start, minOf(t0, t1)); end = minOf(end, maxOf(t0, t1))
            return start <= end
        }
        return axis(a.first, b.first, minLat, maxLat) && axis(a.second, b.second, minLon, maxLon)
    }
}

/** way を先に選別し、次の PBF 走査では必要なノード位置だけ保持する。 */
internal class ExcludedRoadCollector(private val bounds: ExcludedRoadBounds?) {
    private data class RuleWay(val way: OsmWay, val forward: BicycleRoadInfo, val backward: BicycleRoadInfo?)
    private val ways = ArrayList<RuleWay>()
    private val neededNodes = HashSet<Long>()
    private val positions = HashMap<Long, Pair<Double, Double>>()
    var writtenWays = 0
        private set
    val highwayCounts = sortedMapOf<String, Int>()
    val roadwayCounts = sortedMapOf<String, Int>()
    var motorroadCount = 0
        private set

    fun accept(way: OsmWay) {
        if (bounds == null || way.nodeIds.size < 2 || BicycleAccessFilter.isAllowed(way.tags)) return
        val tags = way.tags
        if (tags["highway"] !in CAR_HIGHWAYS && tags["bicycle"] != "dismount") return
        val forward = classifyBicycleRoad(tags, false)
        if (forward.roadway !in setOf(BikeAccess.FORBIDDEN, BikeAccess.DISMOUNT)) return
        val oneway = (tags["oneway"] ?: if (tags["junction"] == "roundabout") "yes" else "no") in setOf("yes", "true", "1", "-1")
        ways.add(RuleWay(way, forward, if (oneway) null else classifyBicycleRoad(tags, true)))
        way.nodeIds.forEach { neededNodes.add(it) }
    }

    fun acceptNode(id: Long, lat: Double, lon: Double) {
        if (id in neededNodes && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            positions[id] = lat to lon
        }
    }

    fun segments(): List<ExcludedSegment> {
        val result = ArrayList<ExcludedSegment>()
        writtenWays = 0; highwayCounts.clear(); roadwayCounts.clear(); motorroadCount = 0
        for ((way, forward, backward) in ways) {
            val before = result.size
            fun add(a: Pair<Double, Double>, b: Pair<Double, Double>, info: BicycleRoadInfo) {
                result.add(ExcludedSegment(a.first, a.second, b.first, b.second, info))
                val highway = way.tags["highway"] ?: "(none)"
                highwayCounts[highway] = (highwayCounts[highway] ?: 0) + 1
                roadwayCounts[info.roadway.name] = (roadwayCounts[info.roadway.name] ?: 0) + 1
                if (info.flags and BicycleRoadInfo.FLAG_MOTORROAD != 0) motorroadCount++
            }
            for (i in 0 until way.nodeIds.size - 1) {
                val a = positions[way.nodeIds[i]] ?: continue
                val b = positions[way.nodeIds[i + 1]] ?: continue
                if (bounds?.intersects(a, b) != true) continue
                add(a, b, forward)
                if (backward != null) add(b, a, backward)
            }
            if (result.size > before) writtenWays++
        }
        return result
    }

    fun printStatistics(segmentCount: Int, out: PrintStream = System.out) {
        out.println("sidecar.segments=$segmentCount; sidecar.ways=$writtenWays; counts below use directed segments")
        highwayCounts.forEach { (name, count) -> out.println("sidecar.highway.$name=$count") }
        BikeAccess.entries.forEach { out.println("sidecar.roadway.$it=${roadwayCounts[it.name] ?: 0}") }
        out.println("sidecar.flag.MOTORROAD=$motorroadCount")
    }

    companion object {
        private val CAR_HIGHWAYS = setOf("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "road")
    }
}

object BikeInfoReindexMain {
    @JvmStatic fun main(args: Array<String>) {
        val usage = "Usage: <input.graph> <input.graph.idx> <PBF> <output.graph.idx> [--ref <route number>] [--sidecar <output.bikerules>]"
        require(args.size >= 4 && args.size % 2 == 0) { usage }
        val options = linkedMapOf<String, String>()
        for (i in 4 until args.size step 2) {
            require(args[i] in setOf("--ref", "--sidecar") && args[i + 1].isNotBlank() && args[i] !in options) { usage }
            options[args[i]] = args[i + 1]
        }
        val output = File(args[3])
        require(!output.exists()) { "Output already exists: $output" }
        val sidecar = options["--sidecar"]?.let(::File)
        if (sidecar != null) {
            // 入力や idx 出力と同じ場所へ誤って書かない。既存ファイルも保護する。
            require(!sidecar.exists() && args.take(4).none { File(it).canonicalFile == sidecar.canonicalFile }) { "Sidecar output already exists or aliases another path: $sidecar" }
        }
        val reindex = BikeInfoReindex.readGraph(File(args[0]), options["--ref"])
        val excluded = sidecar?.let { ExcludedRoadCollector(reindex.bounds) }
        val index = File(args[1])
        validateBikeInfoInput(index, reindex.nodeCount, reindex.size)
        val sink = object : Sink {
            override fun initialize(metaData: Map<String, Any>) = Unit
            override fun process(entityContainer: EntityContainer) {
                val way = entityContainer.entity as? Way ?: return
                val osmWay = OsmWay(way.id, way.wayNodes.map { it.nodeId }.toLongArray(), way.tags.associate { it.key to it.value })
                reindex.accept(osmWay)
                excluded?.accept(osmWay)
            }
            override fun complete() = Unit
            override fun close() = Unit
        }
        File(args[2]).inputStream().buffered().use { input -> OsmosisReader(input).apply { setSink(sink) }.run() }
        if (excluded != null && sidecar != null) {
            val nodeSink = object : Sink {
                override fun initialize(metaData: Map<String, Any>) = Unit
                override fun process(entityContainer: EntityContainer) {
                    val node = entityContainer.entity as? Node ?: return
                    excluded.acceptNode(node.id, node.latitude, node.longitude)
                }
                override fun complete() = Unit
                override fun close() = Unit
            }
            File(args[2]).inputStream().buffered().use { input -> OsmosisReader(input).apply { setSink(nodeSink) }.run() }
            val segments = excluded.segments()
            ExcludedRoadIndex.write(sidecar, segments)
            excluded.printStatistics(segments.size)
            println("sidecar=${sidecar.absolutePath}")
        }
        writeBikeInfoIndex(index, output, reindex.nodeCount, reindex.values)
        reindex.printStatistics()
        println("output=${output.absolutePath}")
    }
}
