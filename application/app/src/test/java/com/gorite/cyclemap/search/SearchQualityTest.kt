package com.gorite.cyclemap.search

import com.gorite.cyclemap.ui.components.shortAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 検索の並び順・別名・重複・住所 (検証で Google マップと差が大きかった点) のテスト。 */
class SearchQualityTest {
    private val userLat = 34.3668
    private val userLon = 132.3780

    private fun r(name: String, category: String, lat: Double, lon: Double, searchText: String? = null, address: String? = null) =
        SearchResult(name, category, lat, lon, searchText = searchText ?: name, address = address)

    @Test
    fun farExactStationBeatsNearPartialMatches() {
        // 実機で「広島駅」の上位がバス停・「広島駅南口」だった
        val items = listOf(
            r("広島駅南口", "highway:bus_stop", 34.3960, 132.4760),
            r("広島駅", "highway:bus_stop", 34.3965, 132.4757),
            r("広島駅前郵便局", "amenity:post_office", 34.3950, 132.4740),
            r("広島駅", "railway:station", 34.3972, 132.4753),
        )
        val ranked = rankSearchResults(items, "広島駅", userLat, userLon)
        assertEquals("railway:station", ranked.first().category)
    }

    @Test
    fun realStationBeatsFartherSameNamedStation() {
        // 駅名だけの OSM の駅 (9km) が、位置のずれた「広島駅」(17km) より上
        val items = listOf(
            r("広島駅", "railway:station", 34.3815, 132.5645),
            r("広島", "railway:station", 34.3974, 132.4758, "広島 ひろしま"),
        )
        assertEquals("広島", rankSearchResults(items, "広島駅", userLat, userLon).first().name)
    }

    @Test
    fun readingQueryFindsKanjiStationFirst() {
        // 「ひろしまえき」で、近い西広島駅より広島駅を上に
        val items = listOf(
            r("西広島", "railway:station", 34.3780, 132.4280, "西広島 にしひろしま nishihiroshima"),
            r("広島", "railway:station", 34.3972, 132.4753, "広島 ひろしま hiroshima"),
        )
        val ranked = rankSearchResults(items, "ひろしまえき", userLat, userLon)
        assertEquals("広島", ranked.first().name)
    }

    @Test
    fun readingExactMatchBeatsNearbyContains() {
        val items = listOf(
            r("県立広島大学前", "highway:bus_stop", 34.3700, 132.3800, "県立広島大学前 けんりつひろしまだいがくまえ"),
            r("広島大学", "amenity:university", 34.4020, 132.7130, "広島大学 ひろしまだいがく hiroshimadaigaku"),
        )
        val ranked = rankSearchResults(items, "ひろしまだいがく", userLat, userLon)
        assertEquals("広島大学", ranked.first().name)
    }

    @Test
    fun aliasesExpandAbbreviationsAndEnglish() {
        assertTrue("スターバックス" in SearchAliases.expand("すたば"))
        assertTrue("スターバックス" in SearchAliases.expand("Starbucks"))
        assertTrue("マクドナルド" in SearchAliases.expand("マック"))
        assertTrue("セブン-イレブン" in SearchAliases.expand("7-Eleven"))
        assertTrue("Hiroshima駅" in SearchAliases.expand("Hiroshima Station"))
        assertEquals("すたば", SearchAliases.expand("すたば").first()) // 元の語が先頭
        assertEquals(listOf("ラーメン"), SearchAliases.expand("ラーメン")) // 別名の無い語は広げない
    }

    @Test
    fun aliasMatchRanksStarbucksForSutaba() {
        val items = listOf(
            r("スタバ紙屋町シャレオ店", "amenity:cafe", 34.3940, 132.4580),
            r("スターバックス", "amenity:cafe", 34.3700, 132.3800, "スターバックス すたーばっくす"),
            r("オートリバースたばこ店", "named", 34.3680, 132.3790, "オートリバースたばこ店 おーとりばーすたばこてん"),
        )
        val ranked = rankSearchResults(items, "すたば", userLat, userLon)
        assertEquals("オートリバースたばこ店", ranked.last().name)
    }

    @Test
    fun nearbySameNamePlacesAreMerged() {
        val items = listOf(
            r("世界遺産 厳島神社", "tourism:attraction", 34.2959, 132.3198),
            r("世界遺産 厳島神社", "tourism:attraction", 34.2961, 132.3200),
            r("ゆめタウン", "shop:mall", 34.3600, 132.3700),
            r("ゆめタウン", "shop:mall", 34.4000, 132.4500), // 別の支店 (遠い) は残す
        )
        val ranked = rankSearchResults(items, "厳島神社", userLat, userLon)
        assertEquals(1, ranked.count { it.name == "世界遺産 厳島神社" })
        assertEquals(2, dedupeNearbySameName(items).count { it.name == "ゆめタウン" })
    }

    @Test
    fun addressDetectionAndShortLabel() {
        assertTrue(looksLikeAddress("袋町5-7"))
        assertTrue(looksLikeAddress("中区袋町"))
        assertFalse(looksLikeAddress("スターバックス"))
        assertEquals("広島市中区袋町5-7", shortAddress("広島市中区 中区袋町5-7"))
        assertEquals("広島市西区井口5丁目", shortAddress("広島県広島市西区井口5丁目"))
        assertEquals(null, shortAddress("7300036"))
        assertEquals(null, shortAddress("〒730-0036"))
    }

    @Test
    fun categoryLabelsNeverShowRawTags() {
        assertEquals("ショッピングモール", formatCategoryLabel("shop:mall"))
        assertEquals("レンタサイクル", formatCategoryLabel("amenity:bicycle_rental"))
        assertEquals("スポット", formatCategoryLabel("named"))
        assertEquals("住所", formatCategoryLabel(ADDRESS_RESULT_CATEGORY))
        for (raw in listOf("shop:hairdresser", "amenity:community_centre", "leisure:playground", "historic:memorial", "craft:brewery", "foo:bar")) {
            assertFalse(raw, ':' in formatCategoryLabel(raw))
        }
        assertNotEquals("shop:mall", formatCategoryLabel("shop:mall"))
    }
}
