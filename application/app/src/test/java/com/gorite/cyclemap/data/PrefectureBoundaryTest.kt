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
        assertFalse(PrefectureBoundaries.contains("no_such_prefecture", 43.0687, 141.3508))
        assertTrue(PrefectureBoundaries.contains("hokkaido", 43.0687, 141.3508)) // 札幌駅 (47 県分のポリゴンがある)
        assertTrue(PrefectureBoundaries.contains("hiroshima", 34.4856, 133.3625))
        assertFalse(PrefectureBoundaries.contains("okayama", 34.4856, 133.3625))
    }

    @Test
    fun every47Prefecture_hasPolygon() {
        assertEquals(47, PrefectureData.ALL.size)
        PrefectureData.ALL.forEach { pref ->
            assertTrue("${pref.id} のポリゴンがありません", PrefectureBoundaries.hasPolygon(pref.id))
        }
        assertFalse(PrefectureBoundaries.hasPolygon("no_such_prefecture"))
    }

    @Test
    fun everyPrefectureCapital_resolvesToItself() {
        // 県庁所在地の位置 (各県のポリゴン内にあることは Python の独立実装で確認済み)
        val capitals = mapOf(
            "hokkaido" to (43.0642 to 141.3469), "aomori" to (40.8244 to 140.7400), "iwate" to (39.7036 to 141.1527),
            "miyagi" to (38.2688 to 140.8721), "akita" to (39.7186 to 140.1024), "yamagata" to (38.2404 to 140.3633),
            "fukushima" to (37.7503 to 140.4676), "ibaraki" to (36.3418 to 140.4468), "tochigi" to (36.5658 to 139.8836),
            "gunma" to (36.3912 to 139.0608), "saitama" to (35.8569 to 139.6489), "chiba" to (35.6047 to 140.1233),
            "tokyo" to (35.6895 to 139.6917), "kanagawa" to (35.4478 to 139.6425), "niigata" to (37.9022 to 139.0236),
            "toyama" to (36.6953 to 137.2113), "ishikawa" to (36.5947 to 136.6256), "fukui" to (36.0652 to 136.2216),
            "yamanashi" to (35.6642 to 138.5684), "nagano" to (36.6513 to 138.1810), "gifu" to (35.3912 to 136.7223),
            "shizuoka" to (34.9769 to 138.3831), "aichi" to (35.1802 to 136.9066), "mie" to (34.7303 to 136.5086),
            "shiga" to (35.0045 to 135.8686), "kyoto" to (35.0214 to 135.7556), "osaka" to (34.6863 to 135.5200),
            "hyogo" to (34.6913 to 135.1830), "nara" to (34.6851 to 135.8049), "wakayama" to (34.2261 to 135.1675),
            "tottori" to (35.5039 to 134.2377), "shimane" to (35.4723 to 133.0505), "okayama" to (34.6618 to 133.9344),
            "hiroshima" to (34.3966 to 132.4596), "yamaguchi" to (34.1861 to 131.4706), "tokushima" to (34.0658 to 134.5593),
            "kagawa" to (34.3401 to 134.0434), "ehime" to (33.8417 to 132.7661), "kochi" to (33.5597 to 133.5311),
            "fukuoka" to (33.6064 to 130.4181), "saga" to (33.2494 to 130.2988), "nagasaki" to (32.7503 to 129.8777),
            "kumamoto" to (32.7898 to 130.7417), "oita" to (33.2382 to 131.6126), "miyazaki" to (31.9111 to 131.4239),
            "kagoshima" to (31.5602 to 130.5581), "okinawa" to (26.2124 to 127.6809),
        )
        assertEquals(47, capitals.size)
        capitals.forEach { (id, pos) ->
            val expected = PrefectureData.ALL.first { it.id == id }.name
            assertEquals("$id の県庁所在地", expected, findPrefectureName(pos.first, pos.second))
        }
    }

    @Test
    fun bboxOverlaps_resolveByPolygon() {
        // 兵庫・岡山・鳥取、神奈川・東京、奈良・大阪など、外接矩形が重なる県境付近
        assertEquals("兵庫県", findPrefectureName(34.8164, 134.6855)) // 姫路駅
        assertEquals("神奈川県", findPrefectureName(35.5308, 139.7029)) // 川崎駅
        assertEquals("奈良県", findPrefectureName(34.6930, 135.7000)) // 生駒
        assertEquals("大阪府", findPrefectureName(34.7790, 135.4770)) // 淀川区
    }
}
