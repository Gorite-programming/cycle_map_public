package com.gorite.cyclemap.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class SearchRankingAndLatencyTest {

    @Test
    fun normalizerHandlesHiraganaKatakanaRomaji() {
        // 全角英数 -> 半角小文字
        assertEquals("abc", SearchNormalizer.normalize("ＡＢＣ"))
        assertEquals("cafe", SearchNormalizer.normalize("ｃａｆｅ"))
        assertEquals("lawson 123", SearchNormalizer.normalize("Ｌａｗｓｏｎ　１２３"))
        // カタカナ -> ひらがな
        assertEquals("こんびに", SearchNormalizer.normalize("コンビニ"))
        // 長音、スペース正規化
        assertEquals("ろーそん", SearchNormalizer.normalize("ローソン"))
        assertEquals("せぶん いれぶん", SearchNormalizer.normalize("セブン　イレブン"))
    }

    @Test
    fun genreDictionaryMatchesKeywords() {
        val convenience = GenreDictionary.matchGenre("近くのコンビニ")
        assertNotNull(convenience)
        assertEquals("convenience", convenience?.id)

        val station = GenreDictionary.matchGenre("広島駅")
        assertNotNull(station)
        assertEquals("station", station?.id)

        val toilet = GenreDictionary.matchGenre("トイレ")
        assertNotNull(toilet)
        assertEquals("toilet", toilet?.id)
    }

    @Test
    fun genericCategoryWordsBecomeCategoryFilters() {
        // 語全体が種類名なら、その種類の接頭辞 (近い順の一覧にする)
        assertEquals(listOf("shop:convenience"), GenreDictionary.genericCategoryPrefixes("コンビニ"))
        assertEquals(listOf("shop:convenience"), GenreDictionary.genericCategoryPrefixes("こんびに"))
        assertEquals(listOf("shop:convenience"), GenreDictionary.genericCategoryPrefixes("近くのコンビニ"))
        assertEquals(listOf("amenity:toilets"), GenreDictionary.genericCategoryPrefixes(" トイレ "))
        assertEquals(
            listOf("amenity:hospital", "amenity:clinic", "amenity:doctors"),
            GenreDictionary.genericCategoryPrefixes("びょういん"),
        )
        // 店名・駅名など、名前で探すべき語は null
        assertNull(GenreDictionary.genericCategoryPrefixes("セブン"))
        assertNull(GenreDictionary.genericCategoryPrefixes("広島駅"))
        assertNull(GenreDictionary.genericCategoryPrefixes("コンビニ前"))
        assertNull(GenreDictionary.genericCategoryPrefixes("道の駅"))
        assertNull(GenreDictionary.genericCategoryPrefixes("近くの"))
        assertNull(GenreDictionary.genericCategoryPrefixes(""))
    }

    @Test
    fun searchRankingPrioritizesExactAndCloserLocations() {
        val candidates = listOf(
            SearchCandidate("1", "ローソン 広島南店", "ろーそん ひろしまみなみてん", "convenience", 34.35, 132.45),
            SearchCandidate("2", "ローソン 広島本通店", "ろーそん ひろしまほんどおりてん", "convenience", 34.39, 132.46), // Closer to user
            SearchCandidate("3", "セブンイレブン", "せぶんいれぶん", "convenience", 34.39, 132.46)
        )

        val ranked = SearchRanking.rankCandidates(
            candidates = candidates,
            query = "ローソン",
            userLat = 34.39,
            userLon = 132.46
        )

        assertEquals(2, ranked.size)
        // Closer Lawson should rank above further Lawson
        assertEquals("2", ranked[0].id)
        assertEquals("1", ranked[1].id)
    }

    @Test
    fun autocompleteLatencyIsUnder120msForLargeDataset() {
        // Generate 10,000 synthetic POIs
        val largeList = (1..10000).map { i ->
            SearchCandidate(
                id = "$i",
                name = if (i % 20 == 0) "ローソン 店舗$i" else "スポット$i",
                searchText = if (i % 20 == 0) "ろーそん てんぽ$i" else "すぽっと$i",
                category = "shop",
                latitude = 34.3 + (i * 0.0001),
                longitude = 132.4 + (i * 0.0001)
            )
        }

        // Measure autocomplete ranking latency
        val elapsed = measureTimeMillis {
            val results = SearchRanking.rankCandidates(
                candidates = largeList,
                query = "ローソン",
                userLat = 34.39,
                userLon = 132.46,
                limit = 10
            )
            assertTrue(results.isNotEmpty())
        }

        // plans.md §7: オートコンプリート < 120ms
        assertTrue("Expected < 120ms, took ${elapsed}ms", elapsed < 120)
    }
}
