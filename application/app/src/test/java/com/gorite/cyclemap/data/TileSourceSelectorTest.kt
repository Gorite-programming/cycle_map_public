package com.gorite.cyclemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TileSourceSelectorTest {

    @Test
    fun selectsYamaguchiAndHiroshimaPmtiles() {
        // Yamaguchi City ~ 34.185, 131.471
        val yama = TileSourceSelector.select(34.185, 131.471)
        assertTrue(yama is TileSourceSelection.Available)
        assertEquals("yamaguchi.pmtiles", (yama as TileSourceSelection.Available).fileName)

        // Hiroshima City ~ 34.385, 132.455
        val hiro = TileSourceSelector.select(34.385, 132.455)
        assertTrue(hiro is TileSourceSelection.Available)
        assertEquals("hiroshima.pmtiles", (hiro as TileSourceSelection.Available).fileName)
    }

    @Test
    fun selectsPmtilesForAllFiveChugokuPrefectures() {
        val cases = listOf(
            Triple(35.50, 134.23, "tottori.pmtiles"),   // 鳥取市
            Triple(35.47, 133.05, "shimane.pmtiles"),   // 松江市
            Triple(34.66, 133.92, "okayama.pmtiles"),   // 岡山市
            Triple(34.39, 132.46, "hiroshima.pmtiles"), // 広島市
            Triple(34.19, 131.47, "yamaguchi.pmtiles"), // 山口市
        )
        for ((lat, lon, expected) in cases) {
            val selection = TileSourceSelector.select(lat, lon)
            assertTrue("$expected at ($lat,$lon): $selection", selection is TileSourceSelection.Available)
            assertEquals(expected, (selection as TileSourceSelection.Available).fileName)
        }
    }

    @Test
    fun legacyHiroshimaOsmFileNameStillResolves() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cyclemap_tile_legacy_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            File(tempDir, "Hiroshima_osm.pmtiles").writeText("legacy hiroshima tiles")
            val resolved = TileSourceSelector.resolveTileForLocation(tempDir, 34.385, 132.455)
            assertTrue(resolved != null && resolved.name.equals("Hiroshima_osm.pmtiles", ignoreCase = true))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun unmappedCoordinatesReturnUnavailable() {
        // Tokyo coordinates (not in Chugoku region)
        val tokyo = TileSourceSelector.select(35.68, 139.76)
        assertTrue(tokyo is TileSourceSelection.Unavailable)
    }

    @Test
    fun fallbackResolvesCaseVariants() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cyclemap_tile_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            val tileFile = File(tempDir, "yamaguchi.pmtiles")
            tileFile.writeText("fake pmtiles content")

            val resolved = TileSourceSelector.resolveTileFile(tempDir, "Yamaguchi.pmtiles")
            assertTrue(resolved != null && resolved.exists())
            assertEquals(tileFile.canonicalPath.lowercase(), resolved?.canonicalPath?.lowercase())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun doesNotUseAnotherPrefecturesTileWhenSelectedTileIsMissing() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "cyclemap_tile_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            File(tempDir, "hiroshima.pmtiles").writeText("Hiroshima tiles")

            val missingYamaguchi = TileSourceSelector.resolveTileForLocation(tempDir, 34.185, 131.471)
            val unsupportedTokyo = TileSourceSelector.resolveTileForLocation(tempDir, 35.68, 139.76)

            assertEquals(null, missingYamaguchi)
            assertEquals(null, unsupportedTokyo)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
