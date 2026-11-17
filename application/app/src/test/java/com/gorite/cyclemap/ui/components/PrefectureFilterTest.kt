package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.data.PrefectureData
import com.gorite.cyclemap.data.PrefectureDataStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefectureFilterTest {
    @Test
    fun regionMapCoversAll47PrefecturesAndEightRegions() {
        assertEquals(8, PrefectureFilter.REGIONS.size)
        assertEquals(47, PrefectureData.ALL.size)
        assertEquals(47, PrefectureFilter.REGION_BY_PREFECTURE_ID.size)
        assertEquals(47, PrefectureFilter.REGION_BY_PREFECTURE_NAME.size)

        PrefectureData.ALL.forEach { pref ->
            assertTrue("${pref.name} の地方 ${pref.region} が一覧にある", pref.region in PrefectureFilter.REGIONS)
            assertEquals(pref.region, PrefectureFilter.REGION_BY_PREFECTURE_ID[pref.id])
            assertEquals(pref.region, PrefectureFilter.REGION_BY_PREFECTURE_NAME[pref.name])
        }

        val countsByRegion = PrefectureData.ALL.groupingBy { it.region }.eachCount()
        assertEquals(1, countsByRegion["北海道"])
        assertEquals(6, countsByRegion["東北"])
        assertEquals(7, countsByRegion["関東"])
        assertEquals(9, countsByRegion["中部"])
        assertEquals(7, countsByRegion["近畿"])
        assertEquals(5, countsByRegion["中国"])
        assertEquals(4, countsByRegion["四国"])
        assertEquals(8, countsByRegion["九州・沖縄"])
    }

    @Test
    fun normalizeRegionHandlesAllAndKyushuOkinawaAlias() {
        assertNull(PrefectureFilter.normalizeRegion(null))
        assertNull(PrefectureFilter.normalizeRegion(""))
        assertNull(PrefectureFilter.normalizeRegion("   "))
        assertNull(PrefectureFilter.normalizeRegion("すべて"))
        assertEquals("九州・沖縄", PrefectureFilter.normalizeRegion("九州沖縄"))
        assertEquals("九州・沖縄", PrefectureFilter.normalizeRegion("九州・沖縄"))
        assertEquals("中国", PrefectureFilter.normalizeRegion(" 中国 "))
    }

    @Test
    fun filterByRegionReturnsMatchingPrefectures() {
        val chugoku = PrefectureFilter.filterAndSort(region = "中国")
        assertEquals(listOf("鳥取県", "島根県", "岡山県", "広島県", "山口県"), chugoku.map { it.name })

        val kyushu = PrefectureFilter.filterAndSort(region = "九州沖縄")
        assertEquals(8, kyushu.size)
        assertTrue(kyushu.any { it.name == "福岡県" })
        assertTrue(kyushu.any { it.name == "沖縄県" })
    }

    @Test
    fun filterByQueryMatchesJapaneseAndRomajiId() {
        val byKanji = PrefectureFilter.filterAndSort(query = "広島")
        assertEquals(listOf("広島県"), byKanji.map { it.name })

        val byRomaji = PrefectureFilter.filterAndSort(query = "  YAMAGUCHI ")
        assertEquals(listOf("山口県"), byRomaji.map { it.name })

        val combined = PrefectureFilter.filterAndSort(region = "近畿", query = "京都")
        assertEquals(listOf("京都府"), combined.map { it.name })
    }

    @Test
    fun currentLocationPrefectureIsPlacedFirstWhenIncludedInFilter() {
        val allWithHiroshima = PrefectureFilter.filterAndSort(currentPrefectureName = "広島県")
        assertEquals("広島県", allWithHiroshima.first().name)
        assertEquals(47, allWithHiroshima.size)
        assertEquals("北海道", allWithHiroshima[1].name)

        val chugokuWithYamaguchi = PrefectureFilter.filterAndSort(
            region = "中国",
            currentPrefectureName = "山口県",
        )
        assertEquals(listOf("山口県", "鳥取県", "島根県", "岡山県", "広島県"), chugokuWithYamaguchi.map { it.name })

        // 別の地方で絞り込んだときは他地方の現在地県を混ぜない
        val kantoWithYamaguchi = PrefectureFilter.filterAndSort(
            region = "関東",
            currentPrefectureName = "山口県",
        )
        assertEquals(7, kantoWithYamaguchi.size)
        assertFalse(kantoWithYamaguchi.any { it.name == "山口県" })
    }

    @Test
    fun sortInstalledFirstPutsCurrentPrefectureAtTop() {
        val installed = listOf(
            PrefectureDataStatus("hiroshima", "広島県", true, true, true, false, false, 100L),
            PrefectureDataStatus("yamaguchi", "山口県", true, true, true, false, false, 200L),
            PrefectureDataStatus("okayama", "岡山県", true, false, false, false, false, 50L),
        )
        val sorted = PrefectureFilter.sortInstalledFirst(installed, "山口県")
        assertEquals(listOf("yamaguchi", "hiroshima", "okayama"), sorted.map { it.id })
    }
}
