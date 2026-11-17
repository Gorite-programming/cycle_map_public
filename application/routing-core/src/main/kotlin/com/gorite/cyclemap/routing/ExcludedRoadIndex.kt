package com.gorite.cyclemap.routing

import java.io.*
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import kotlin.math.*

data class ExcludedSegment(
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
    val info: BicycleRoadInfo,
)

/** 経路探索には使わない通行ルール専用の線分索引。読み込み後は変更しない。 */
class ExcludedRoadIndex private constructor(private val segments: List<ExcludedSegment>) {
    private val grid: Map<Long, IntArray>
    private val longSegments: IntArray

    init {
        val cells = HashMap<Long, MutableList<Int>>()
        val long = ArrayList<Int>()
        segments.forEachIndexed { index, s ->
            val x0 = cell(min(s.fromLon, s.toLon)); val x1 = cell(max(s.fromLon, s.toLon))
            val y0 = cell(min(s.fromLat, s.toLat)); val y1 = cell(max(s.fromLat, s.toLat))
            // 異常に長い線分でも格子の展開でメモリを使い切らない。
            if ((x1 - x0 + 1L) * (y1 - y0 + 1L) > 4096) long.add(index)
            else for (x in x0..x1) for (y in y0..y1) cells.getOrPut(key(x, y)) { ArrayList() }.add(index)
        }
        grid = cells.mapValues { it.value.toIntArray() }
        longSegments = long.toIntArray()
    }

    fun candidatesNear(lat: Double, lon: Double, radiusMeters: Double = 150.0): List<RoadCandidate> {
        if (!validPoint(lat, lon) || !radiusMeters.isFinite() || radiusMeters < 0) return emptyList()
        val scale = METERS_PER_DEGREE * cos(Math.toRadians(lat))
        val dy = radiusMeters / METERS_PER_DEGREE
        val dx = if (scale > 1e-9) radiusMeters / scale else 360.0
        val x0 = cell((lon - dx).coerceAtLeast(-180.0)); val x1 = cell((lon + dx).coerceAtMost(180.0))
        val y0 = cell((lat - dy).coerceAtLeast(-90.0)); val y1 = cell((lat + dy).coerceAtMost(90.0))
        val indices = HashSet<Int>()
        if ((x1 - x0 + 1L) * (y1 - y0 + 1L) > grid.size.toLong() * 4 + 4096) {
            indices.addAll(segments.indices)
        } else {
            for (x in x0..x1) for (y in y0..y1) grid[key(x, y)]?.forEach { indices.add(it) }
            longSegments.forEach { indices.add(it) }
        }
        return indices.sorted().mapNotNull { i ->
            val s = segments[i]
            val x = (s.fromLon - lon) * scale; val y = (s.fromLat - lat) * METERS_PER_DEGREE
            val vx = (s.toLon - s.fromLon) * scale; val vy = (s.toLat - s.fromLat) * METERS_PER_DEGREE
            val lengthSquared = vx * vx + vy * vy
            val t = if (lengthSquared == 0.0) 0.0 else (-(x * vx + y * vy) / lengthSquared).coerceIn(0.0, 1.0)
            if (hypot(x + t * vx, y + t * vy) <= radiusMeters) {
                RoadCandidate(-(i + 1), s.fromLat, s.fromLon, s.toLat, s.toLon, s.info)
            } else null
        }
    }

    companion object {
        private const val MAGIC = "CYCLEMAP_BIKERULES_V1"
        private const val METERS_PER_DEGREE = Math.PI * 6_371_000.0 / 180.0
        private fun cell(value: Double) = floor(value / 0.01).toInt()
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        private fun validPoint(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

        fun write(file: File, segments: List<ExcludedSegment>) {
            val target = file.toPath().toAbsolutePath()
            val temporary = Files.createTempFile(target.parent, ".bikerules-", ".tmp")
            try {
                DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { out ->
                    out.writeUTF(MAGIC); out.writeInt(segments.size)
                    for (s in segments) {
                        require(validPoint(s.fromLat, s.fromLon) && validPoint(s.toLat, s.toLon))
                        out.writeInt((s.fromLat * 1e6).roundToInt()); out.writeInt((s.fromLon * 1e6).roundToInt())
                        out.writeInt((s.toLat * 1e6).roundToInt()); out.writeInt((s.toLon * 1e6).roundToInt())
                        out.writeInt(s.info.encode())
                    }
                }
                try {
                    Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        fun load(file: File): ExcludedRoadIndex? = try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readUTF() == MAGIC)
                val count = input.readInt()
                require(count >= 0 && file.length() == 2L + MAGIC.length + 4L + count * 20L)
                val segments = ArrayList<ExcludedSegment>(count)
                repeat(count) {
                    val a = input.readInt() / 1e6; val b = input.readInt() / 1e6
                    val c = input.readInt() / 1e6; val d = input.readInt() / 1e6
                    val bits = input.readInt(); val info = BicycleRoadInfo.decode(bits)
                    require(validPoint(a, b) && validPoint(c, d) && info.encode() == bits)
                    segments.add(ExcludedSegment(a, b, c, d, info))
                }
                require(input.read() == -1)
                ExcludedRoadIndex(segments)
            }
        } catch (_: Exception) {
            null
        }
    }
}
