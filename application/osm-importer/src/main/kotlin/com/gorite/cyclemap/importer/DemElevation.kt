package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphNode
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * 国土地理院の標高タイル (dem_png, z14, 約10m メッシュ) から、ノードの標高を引く。
 * タイルは `<dir>/<x>/<y>.png` (scripts/build_terrain.py のキャッシュ `work/terrain/dem14` と同じ配置)。
 * タイルが無い・欠測 (海など) の場所は NaN を返す。0m 扱いにすると海岸で実在しない急勾配ができるため。
 */
class DemElevation(private val dir: File, private val zoom: Int = 14) {
    private var cachedKey = Long.MIN_VALUE
    private var cachedTile: FloatArray? = null

    /** 各ノードの標高 (m)。不明は NaN。ノードをタイルごとにまとめて、同じタイルを何度も読まない。 */
    fun elevations(nodes: Collection<GraphNode>): Map<Long, Float> {
        val list = nodes.toList()
        val n = list.size
        val xf = DoubleArray(n)
        val yf = DoubleArray(n)
        val keys = LongArray(n)
        for (i in 0 until n) {
            xf[i] = worldX(list[i].longitude)
            yf[i] = worldY(list[i].latitude)
            keys[i] = tileKey(floor(xf[i]).toInt(), floor(yf[i]).toInt())
        }
        val order = (0 until n).sortedBy { keys[it] }
        val result = HashMap<Long, Float>(n * 2)
        for (i in order) {
            val tx = floor(xf[i]).toInt()
            val ty = floor(yf[i]).toInt()
            val tile = tile(tx, ty)
            result[list[i].id] = if (tile == null) Float.NaN else sample(tile, (xf[i] - tx) * TILE - 0.5, (yf[i] - ty) * TILE - 0.5)
        }
        return result
    }

    private fun tile(tx: Int, ty: Int): FloatArray? {
        val key = tileKey(tx, ty)
        if (key == cachedKey) return cachedTile
        cachedKey = key
        cachedTile = decode(File(dir, "$tx/$ty.png"))
        return cachedTile
    }

    private fun worldX(lon: Double) = (lon + 180.0) / 360.0 * (1 shl zoom)

    private fun worldY(lat: Double): Double {
        val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(rad) + 1.0 / kotlin.math.cos(rad)) / PI) / 2.0 * (1 shl zoom)
    }

    private fun tileKey(tx: Int, ty: Int) = tx.toLong() * (1L shl 24) + ty

    companion object {
        const val TILE = 256

        /** dem_png の 1 タイルを標高 (m) の配列にする。読めなければ null。欠測画素は NaN。 */
        fun decode(file: File): FloatArray? {
            if (!file.isFile || file.length() == 0L) return null
            val image = try { ImageIO.read(file) } catch (_: Exception) { null } ?: return null
            if (image.width != TILE || image.height != TILE) return null
            val out = FloatArray(TILE * TILE)
            for (y in 0 until TILE) for (x in 0 until TILE) {
                val rgb = image.getRGB(x, y)
                val v = ((rgb shr 16) and 0xff) * 65536 + ((rgb shr 8) and 0xff) * 256 + (rgb and 0xff)
                out[y * TILE + x] = when {
                    v == 1 shl 23 -> Float.NaN
                    v < 1 shl 23 -> v * 0.01f
                    else -> (v - (1 shl 24)) * 0.01f
                }
            }
            return out
        }

        /** タイル内の画素座標 (px, py) を双一次補間。4 画素のどれかが欠測なら NaN。端はタイル内に丸める。 */
        fun sample(tile: FloatArray, px: Double, py: Double): Float {
            val x = px.coerceIn(0.0, TILE - 1.0)
            val y = py.coerceIn(0.0, TILE - 1.0)
            val x0 = floor(x).toInt().coerceAtMost(TILE - 2)
            val y0 = floor(y).toInt().coerceAtMost(TILE - 2)
            val fx = (x - x0).toFloat()
            val fy = (y - y0).toFloat()
            val a = tile[y0 * TILE + x0]
            val b = tile[y0 * TILE + x0 + 1]
            val c = tile[(y0 + 1) * TILE + x0]
            val d = tile[(y0 + 1) * TILE + x0 + 1]
            if (a.isNaN() || b.isNaN() || c.isNaN() || d.isNaN()) return Float.NaN
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        }

        /** これより短い辺は、10m メッシュの誤差が勾配に大きく出るため勾配なし (中立) にする。 */
        const val MIN_GRADE_EDGE_METERS = 15.0

        /** 道路として現実的な上限 (橋・トンネル・DEM の段差で出る異常値を抑える)。 */
        const val MAX_ABS_GRADE_PERCENT = 30.0

        /** 辺の勾配 (%)。from → to の向きで、上りが正。標高不明や短い辺は null。 */
        fun edgeGradePercent(distanceMeters: Double, fromElevation: Float?, toElevation: Float?): Double? {
            if (fromElevation == null || toElevation == null || fromElevation.isNaN() || toElevation.isNaN()) return null
            if (distanceMeters < MIN_GRADE_EDGE_METERS) return null
            val g = (toElevation - fromElevation) / distanceMeters * 100.0
            return g.coerceIn(-MAX_ABS_GRADE_PERCENT, MAX_ABS_GRADE_PERCENT)
        }
    }
}
