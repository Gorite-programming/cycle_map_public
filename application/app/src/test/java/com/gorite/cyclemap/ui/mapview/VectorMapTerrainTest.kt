package com.gorite.cyclemap.ui.mapview

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VectorMapTerrainTest {
    private val baseStyle = """
        {"version":8,"sources":{"protomaps":{"type":"vector","url":"x"}},
         "layers":[
           {"id":"background","type":"background"},
           {"id":"water","type":"fill","source":"protomaps","source-layer":"water"},
           {"id":"roads_tunnels_other_casing","type":"line","source":"protomaps","source-layer":"roads"},
           {"id":"roads_major","type":"line","source":"protomaps","source-layer":"roads"},
           {"id":"buildings-2d","type":"fill","source":"protomaps","source-layer":"buildings"},
           {"id":"address_label","type":"symbol","source":"protomaps","source-layer":"buildings"},
           {"id":"places_locality","type":"symbol","source":"protomaps","source-layer":"places"}
         ]}
    """.trimIndent()

    private fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "cyclemap_terrain_${System.nanoTime()}").also { it.mkdirs() }

    private fun ids(json: String): List<String> =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("layers").map { it.asJsonObject.get("id").asString }

    @Test
    fun styleIsUnchangedWhenTheProvinceHasNoTerrainFiles() {
        assertEquals(baseStyle, injectTerrain(baseStyle, isNight = false, terrain = TerrainFiles(null, null)))
    }

    @Test
    fun terrainLayersSitBetweenGroundAndRoadsAndLabelsBeforeTheFirstSymbol() {
        val dir = tempDir()
        try {
            val hill = File(dir, "x.hillshade.pmtiles").apply { writeText("h") }
            val cont = File(dir, "x.contours.pmtiles").apply { writeText("c") }
            val out = injectTerrain(baseStyle, isNight = true, terrain = TerrainFiles(hill, cont))
            val order = ids(out)
            // 地面 (water) < 陰影 < 等高線 < 道路 < 建物 < 等高線ラベル < 住所ラベル
            fun idx(id: String) = order.indexOf(id).also { assertTrue("$id missing in $order", it >= 0) }
            assertTrue(idx("water") < idx("terrain_hillshade"))
            assertTrue(idx("terrain_hillshade") < idx("terrain_contour_minor"))
            assertTrue(idx("terrain_contour_minor") < idx("terrain_contour_index"))
            assertTrue(idx("terrain_contour_index") < idx("roads_tunnels_other_casing"))
            assertTrue(idx("buildings-2d") < idx("terrain_contour_labels"))
            assertTrue(idx("terrain_contour_labels") < idx("address_label"))
            val sources = JsonParser.parseString(out).asJsonObject.getAsJsonObject("sources")
            assertTrue(sources.get(TERRAIN_HILLSHADE_SOURCE).asJsonObject.get("url").asString.startsWith("pmtiles://file://"))
            assertEquals("raster", sources.get(TERRAIN_HILLSHADE_SOURCE).asJsonObject.get("type").asString)
            assertEquals("vector", sources.get(TERRAIN_CONTOURS_SOURCE).asJsonObject.get("type").asString)
            // 元のレイヤーは1つも失われない
            assertTrue(order.containsAll(ids(baseStyle)))
            assertEquals(ids(baseStyle).size + 4, order.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun onlyTheAvailableKindIsAdded() {
        val dir = tempDir()
        try {
            val cont = File(dir, "x.contours.pmtiles").apply { writeText("c") }
            val out = injectTerrain(baseStyle, isNight = false, terrain = TerrainFiles(null, cont))
            assertTrue(ids(out).contains("terrain_contour_index"))
            assertTrue(!ids(out).contains("terrain_hillshade"))
            val sources = JsonParser.parseString(out).asJsonObject.getAsJsonObject("sources")
            assertNull(sources.get(TERRAIN_HILLSHADE_SOURCE))
            assertNotNull(sources.get(TERRAIN_CONTOURS_SOURCE))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun terrainFilesAreFoundNextToTheProvinceTilesIncludingTheLegacyName() {
        val dir = tempDir()
        try {
            File(dir, "hiroshima.contours.pmtiles").writeText("c")
            File(dir, "hiroshima.hillshade.pmtiles").writeText("h")
            File(dir, "yamaguchi.contours.pmtiles").writeText("c")
            val legacy = terrainFilesFor(File(dir, "Hiroshima_osm.pmtiles").absolutePath)
            assertNotNull(legacy.hillshade); assertNotNull(legacy.contours)
            val yamaguchi = terrainFilesFor(File(dir, "yamaguchi.pmtiles").absolutePath)
            assertNull(yamaguchi.hillshade); assertNotNull(yamaguchi.contours)
            assertTrue(terrainFilesFor(File(dir, "tottori.pmtiles").absolutePath).isEmpty)
        } finally {
            dir.deleteRecursively()
        }
    }
}
