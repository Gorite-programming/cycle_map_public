package com.gorite.cyclemap.data

import java.util.concurrent.ConcurrentHashMap

/**
 * 47 都道府県の行政界ポリゴン (国土数値情報ベース、`boundaries/<Romaji>.geojson`)。
 * 島嶼も含め元データの全リングを持つ。
 *
 * データは `src/main/resources/prefecture_polygons/<県ID>.txt` に置き、必要な県のファイルだけを遅延で読む
 * (Kotlin の定数に埋め込むと 1 定数 64KB の上限を超え、起動時の負荷にもなるため)。
 * ファイルは `scripts/build_prefecture_polygons.py` が `boundaries/` から作る。境界を更新したら再実行する。
 *
 * 形式: ポリゴン同士は ';'、ポリゴン内のリング (先頭が外周、以降は穴) は '|'、リングの点は 'lon,lat' を空白区切り。
 *
 * 用途: `findPrefectureName` の県判定。
 * 純粋Kotlin (Android非依存)・JVMテスト可能。
 */
internal object PrefectureBoundaries {
    private data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
        fun contains(lat: Double, lon: Double): Boolean = lat in south..north && lon in west..east
    }

    // 東京の島嶼を含め、アプリのタイル取得用bboxとは独立した行政界の範囲。
    private val metroBounds = mapOf(
        "tokyo" to Bounds(138.94287, 24.22485, 153.98607, 35.89826),
        "aichi" to Bounds(136.67105, 34.57829, 137.83812, 35.4242),
        "osaka" to Bounds(135.09202, 34.27182, 135.74659, 35.05104),
    )

    fun findMetroPrefecture(latitude: Double, longitude: Double): String? =
        metroBounds.entries.firstOrNull { (id, bounds) ->
            bounds.contains(latitude, longitude) && contains(id, latitude, longitude)
        }?.key

    fun hasMetroBoundary(prefectureId: String): Boolean = prefectureId in metroBounds

    /** 県ID → ポリゴン (読み込み済みのもの)。ポリゴンの無い県は空リストを入れて、読み直しを繰り返さない。 */
    private val cache = ConcurrentHashMap<String, List<List<DoubleArray>>>()

    private fun polygonsOf(prefectureId: String): List<List<DoubleArray>> =
        cache.getOrPut(prefectureId) { load(prefectureId) }

    private fun load(prefectureId: String): List<List<DoubleArray>> {
        val stream = PrefectureBoundaries::class.java.getResourceAsStream("/prefecture_polygons/$prefectureId.txt")
            ?: return emptyList()
        return stream.use { decode(it.readBytes().toString(Charsets.UTF_8)) }
    }

    internal fun decode(encoded: String): List<List<DoubleArray>> =
        encoded.split(";").map { poly ->
            poly.split("|").map { ring ->
                val nums = ring.split(" ").flatMap { it.split(",") }
                DoubleArray(nums.size) { i -> nums[i].toDouble() }
            }
        }

    /** 指定県の行政界ポリゴンがあるか (県IDが 47 県のいずれかなら true)。 */
    fun hasPolygon(prefectureId: String): Boolean = polygonsOf(prefectureId).isNotEmpty()

    /** 指定県の行政界内に含まれるか。データの無い県IDでは false (bbox方式へフォールバック)。 */
    fun contains(prefectureId: String, latitude: Double, longitude: Double): Boolean {
        val polys = polygonsOf(prefectureId)
        for (poly in polys) {
            if (poly.isEmpty()) continue
            if (!pointInRing(poly[0], longitude, latitude)) continue
            var inHole = false
            for (i in 1 until poly.size) {
                if (pointInRing(poly[i], longitude, latitude)) {
                    inHole = true
                    break
                }
            }
            if (!inHole) return true
        }
        return false
    }

    /** 県ID → 多角形の外接範囲 [南, 北, 西, 東]。点が明らかに外の県を、多角形の判定の前に外すため。 */
    private val polygonBoxes = ConcurrentHashMap<String, DoubleArray>()

    private fun polygonBoxOf(prefectureId: String): DoubleArray = polygonBoxes.getOrPut(prefectureId) {
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (poly in polygonsOf(prefectureId)) {
            val outer = poly.firstOrNull() ?: continue
            var i = 0
            while (i + 1 < outer.size) {
                val x = outer[i]; val y = outer[i + 1]
                if (y < s) s = y; if (y > n) n = y; if (x < w) w = x; if (x > e) e = x
                i += 2
            }
        }
        doubleArrayOf(s, n, w, e)
    }

    /**
     * [prefectureIds] の中から、行政界の多角形が点を含む県を探す。県の矩形 (PrefectureData の bounds) は
     * 実際の県域より狭いことがある (例: 山口県の矩形は東端が岩国まで届かない) ので、矩形で候補が見つからないときに使う。
     */
    fun findByPolygon(prefectureIds: List<String>, latitude: Double, longitude: Double): String? =
        prefectureIds.firstOrNull { id ->
            val box = polygonBoxOf(id)
            latitude in box[0]..box[1] && longitude in box[2]..box[3] && contains(id, latitude, longitude)
        }

    /** ray casting (even-odd)。x=lon, y=lat。 */
    private fun pointInRing(ring: DoubleArray, x: Double, y: Double): Boolean {
        var inside = false
        var j = ring.size - 2
        var i = 0
        while (i < ring.size) {
            val xi = ring[i]
            val yi = ring[i + 1]
            val xj = ring[j]
            val yj = ring[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
            i += 2
        }
        return inside
    }
}
