package com.gorite.cyclemap.importer

import com.gorite.cyclemap.routing.GraphEdge
import com.gorite.cyclemap.routing.GraphNode
import com.gorite.cyclemap.routing.LazyMappedRoadGraph
import com.gorite.cyclemap.routing.RoadGraph
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemElevationTest {
    /** dem_png の符号化 (x = R*65536 + G*256 + B, 単位 0.01m, 負は 2^24 を引く, 2^23 は欠測) で単色タイルを作る。 */
    private fun writeTile(dir: File, tx: Int, ty: Int, pixel: (Int, Int) -> Int) {
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 256) for (x in 0 until 256) image.setRGB(x, y, pixel(x, y))
        File(dir, "$tx").mkdirs()
        ImageIO.write(image, "png", File(dir, "$tx/$ty.png"))
    }

    private fun encode(meters: Double): Int {
        val v = Math.round(meters * 100).toInt()
        return if (v >= 0) v else v + (1 shl 24)
    }

    private fun tileOf(lat: Double, lon: Double): Pair<Int, Int> {
        val n = 1 shl 14
        val x = floor((lon + 180.0) / 360.0 * n).toInt()
        val rad = Math.toRadians(lat)
        val y = floor((1.0 - Math.log(Math.tan(rad) + 1.0 / Math.cos(rad)) / Math.PI) / 2.0 * n).toInt()
        return x to y
    }

    @Test
    fun `標高の符号化は正負と欠測を正しく復号する`() {
        val dir = createTempDir("dem")
        writeTile(dir, 1, 1) { x, _ -> if (x < 100) encode(123.45) else if (x < 200) encode(-5.5) else 1 shl 23 }
        val tile = assertNotNull(DemElevation.decode(File(dir, "1/1.png")))
        assertEquals(123.45f, tile[0], 0.011f)
        assertEquals(-5.5f, tile[150], 0.011f)
        assertTrue(tile[250].isNaN())
        dir.deleteRecursively()
    }

    @Test
    fun `タイルが無い場所の標高は NaN`() {
        val dir = createTempDir("dem")
        val e = DemElevation(dir).elevations(listOf(GraphNode(1, 34.4, 132.4)))
        assertTrue(e.getValue(1).isNaN())
        dir.deleteRecursively()
    }

    @Test
    fun `一様な標高のタイルではノードの標高がその値になる`() {
        val dir = createTempDir("dem")
        val (tx, ty) = tileOf(34.4, 132.4)
        writeTile(dir, tx, ty) { _, _ -> encode(50.0) }
        val e = DemElevation(dir).elevations(listOf(GraphNode(1, 34.4, 132.4)))
        assertEquals(50.0f, e.getValue(1), 0.02f)
        dir.deleteRecursively()
    }

    @Test
    fun `勾配は上りが正で、短い辺と不明な標高は null`() {
        assertEquals(10.0, DemElevation.edgeGradePercent(100.0, 0f, 10f)!!, 1e-9)
        assertEquals(-5.0, DemElevation.edgeGradePercent(200.0, 10f, 0f)!!, 1e-9)
        assertNull(DemElevation.edgeGradePercent(14.9, 0f, 5f))
        assertNull(DemElevation.edgeGradePercent(100.0, Float.NaN, 5f))
        assertNull(DemElevation.edgeGradePercent(100.0, null, 5f))
    }

    @Test
    fun `勾配は現実的な上限 30 パーセントに丸める`() {
        assertEquals(30.0, DemElevation.edgeGradePercent(20.0, 0f, 50f)!!, 1e-9)
        assertEquals(-30.0, DemElevation.edgeGradePercent(20.0, 50f, 0f)!!, 1e-9)
    }

    @Test
    fun `標高タイルを渡すとグラフに勾配が書かれ、渡さなければ従来どおり勾配なし`() {
        val dir = createTempDir("dem")
        val (tx, ty) = tileOf(34.4, 132.4)
        // 画素 x が 1 増えるごとに 1m 上がる東上がりの斜面
        writeTile(dir, tx, ty) { x, _ -> encode(x.toDouble()) }
        val a = GraphNode(1, 34.4, 132.4)
        val b = GraphNode(2, 34.4, 132.4003) // 約 27m 東
        val dist = com.gorite.cyclemap.routing.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val graph = RoadGraph(
            linkedMapOf(1L to a, 2L to b),
            linkedMapOf(
                1L to mutableListOf(GraphEdge(1, 2, dist, "residential")),
                2L to mutableListOf(GraphEdge(2, 1, dist, "residential")),
            ),
        )
        fun gradesOf(demDir: File?): List<Float> {
            val out = File(dir, if (demDir == null) "plain.graph" else "dem.graph")
            GraphBinaryWriter(demDir).write(graph, out)
            // idx を作るのは main の役目。ここでは writeGraphIndex を呼べないので、直接ファイルを読み戻す代わりに
            // 勾配の有無はサイズ差で確かめる (勾配ありの辺は 1 辺あたり 8 バイト増える)。
            return listOf(out.length().toFloat())
        }
        val plain = gradesOf(null)[0]
        val withDem = gradesOf(dir)[0]
        assertEquals(16f, withDem - plain, 0f) // 2 辺 x 8 バイト
        dir.deleteRecursively()
    }
}
