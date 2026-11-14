package com.gorite.cyclemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行政界ポリゴン併用の県判定 (BUG-22)。
 * 期待値は各県GeoJSONへの独立実装 (Python ray casting) で事前検証済み。
 */
class PrefectureBoundaryTest {

    @Test
    fun fukuyama_isHiroshima_despiteNarrowerOkayamaBbox() {
        // 福山駅: 岡山・広島の両bboxに含まれ、面積では岡山が狭い。ポリゴンで広島に解決する。
        assertEquals("広島県", findPrefectureName(34.4856, 133.3625))
    }

    @Test
    fun overlapStrip_keepsOkayamaWhereCorrect() {
        // 重複帯でも岡山県内は岡山のまま (岡山駅・倉敷・井原)。
        assertEquals("岡山県", findPrefectureName(34.6654, 133.9189))
        assertEquals("岡山県", findPrefectureName(34.5851, 133.7719))
        assertEquals("岡山県", findPrefectureName(34.5972, 133.4631))
    }

    @Test
    fun singleMatch_unchanged() {
        assertEquals("広島県", findPrefectureName(34.3976, 132.4756))
        assertEquals("広島県", findPrefectureName(34.8578, 133.0167))
        assertEquals("山口県", findPrefectureName(34.1785, 131.4737))
        // 岩国駅付近: 広島・山口の両bboxに一致し、ポリゴンで山口に解決する。
        assertEquals("山口県", findPrefectureName(34.1663, 132.1746))
        assertEquals("山口県", findPrefectureName(33.9578, 130.9417))
    }

    @Test
    fun outsideJapan_isUnassigned() {
        assertNull(findPrefectureName(30.0, 135.0))
    }

    @Test
    fun metroPrefectures_selectTheirOfflineFiles() {
        val stations = listOf(
            Triple(35.6812, 139.7671, "Tokyo"),
            Triple(35.1709, 136.8815, "Aichi"),
            Triple(34.7025, 135.4959, "Osaka"),
            Triple(27.0944, 142.1917, "Tokyo"), // 小笠原・父島
        )
        for ((lat, lon, name) in stations) {
            val graph = RoutingGraphSelector.select(lat, lon) as RoutingGraphSelection.Available
            val search = SearchDbSelector.select(lat, lon) as SearchDbSelection.Available
            val tile = TileSourceSelector.select(lat, lon) as TileSourceSelection.Available
            assertEquals("$name.graph", graph.fileName)
            assertEquals("$name.search.db", search.fileName)
            assertEquals("${name.lowercase()}.pmtiles", tile.fileName)
        }
    }

    @Test
    fun adjacentCity_isNotMisidentifiedAsTokyo() {
        assertEquals("千葉県", findPrefectureName(35.7282, 139.9080)) // 市川駅
    }

    @Test
    fun boundaries_unknownIdIsFalse() {
        assertFalse(PrefectureBoundaries.contains("hokkaido", 43.0687, 141.3508))
        assertTrue(PrefectureBoundaries.contains("hiroshima", 34.4856, 133.3625))
        assertFalse(PrefectureBoundaries.contains("okayama", 34.4856, 133.3625))
    }
}
