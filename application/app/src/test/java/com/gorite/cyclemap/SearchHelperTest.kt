package com.gorite.cyclemap

import com.gorite.cyclemap.search.*
import com.gorite.cyclemap.ui.cycling.NearbySpot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SearchHelperTest {

    @Test
    fun searchPlaces_returnsEmptyWhenFileDoesNotExist() {
        val nonExistent = File("/tmp/non_existent_search.db")
        val result = searchPlaces(nonExistent, "test")
        assertEquals(emptyList<SearchResult>(), result)
    }

    @Test
    fun buildFtsQuery_formatsPrefixQuery() {
        assertEquals("\"セブン\"* \"イレブン\"*", buildFtsQuery("セブン イレブン"))
        assertEquals("\"広島\"*", buildFtsQuery("広島"))
        assertEquals("\"cafe\"*", buildFtsQuery("cafe"))
        assertEquals("\"sebun'irebun\"*", buildFtsQuery("sebun'irebun"))
        assertEquals("\"ダブル\"\"クォート\"*", buildFtsQuery("ダブル\"クォート"))
    }

    @Test
    fun buildLikeTextConditions_withSearchTextColumn_singleWord() {
        val (whereClause, args) = buildLikeTextConditions("せぶん", "search_text")
        assertEquals("search_text LIKE ? ESCAPE '\\'", whereClause)
        assertEquals(listOf("%せぶん%"), args)
    }

    @Test
    fun buildLikeTextConditions_withSearchTextColumn_multiWordAndMatching() {
        // FTS5非対応時の複数単語AND結合 & 中間一致 (%word%)
        val (whereClause, args) = buildLikeTextConditions("せぶん いれぶん", "search_text")
        assertEquals("search_text LIKE ? ESCAPE '\\' AND search_text LIKE ? ESCAPE '\\'", whereClause)
        assertEquals(listOf("%せぶん%", "%いれぶん%"), args)
    }

    @Test
    fun buildLikeTextConditions_withLegacyNameColumn_multiWord() {
        // レガシーDB (search_textカラムなし) での name 検索
        val (whereClause, args) = buildLikeTextConditions("ローソン 広島", "name")
        assertEquals("name LIKE ? ESCAPE '\\' AND name LIKE ? ESCAPE '\\'", whereClause)
        assertEquals(listOf("%ローソン%", "%広島%"), args)
    }

    @Test
    fun buildLikeTextConditions_escapesWildcardsInUserInput() {
        // % と _ はリテラル扱い (全件一致を防ぐ)
        val (_, args) = buildLikeTextConditions("100%_a", "name")
        assertEquals(listOf("%100\\%\\_a%"), args)
        assertEquals("a\\\\b", escapeLike("a\\b"))
    }

    @Test
    fun shouldShowFtsNotice_returnsFalseWhenSearchTextAvailableEvenIfFts5Unsupported() {
        // FTS5非対応でも search_text カラムがあれば曖昧検索可能なのでバナーは表示しない
        val shouldShow = shouldShowFtsNotice(ftsSupported = false, hasSearchText = true, hasShownThisSession = false)
        assertEquals(false, shouldShow)
    }

    @Test
    fun shouldShowFtsNotice_returnsTrueOnlyWhenBothFtsAndSearchTextUnavailable() {
        // FTS5も非対応で search_text も無いレガシーDBの場合のみ通知
        val shouldShow = shouldShowFtsNotice(ftsSupported = false, hasSearchText = false, hasShownThisSession = false)
        assertEquals(true, shouldShow)

        // ただしセッション中に既に表示済みの場合は再表示しない
        val shouldShowSecondTime = shouldShowFtsNotice(ftsSupported = false, hasSearchText = false, hasShownThisSession = true)
        assertEquals(false, shouldShowSecondTime)
    }

    @Test
    fun shouldShowFtsNotice_returnsFalseWhenFtsSupported() {
        val shouldShow = shouldShowFtsNotice(ftsSupported = true, hasSearchText = false, hasShownThisSession = false)
        assertEquals(false, shouldShow)
    }

    @Test
    fun placesCacheAndNotice_resetSafely() {
        clearPlacesHasSearchTextCacheForTesting()
        resetFts5NoticeForTesting()
    }

    @Test
    fun extractStationQuery_handlesVariousInputs() {
        assertEquals("広島", extractStationQuery("広島駅"))
        assertEquals("新白島", extractStationQuery("新白島駅"))
        assertEquals("ひろしま", extractStationQuery("ひろしまえき"))
        assertEquals("hiroshima", extractStationQuery("hiroshimaeki"))
        assertEquals(null, extractStationQuery("駅"))
        assertEquals(null, extractStationQuery("えき"))
        assertEquals(null, extractStationQuery("eki"))
        assertEquals(null, extractStationQuery("セブンイレブン"))
    }

    @Test
    fun categoryPriority_ranksCategoriesCorrectly() {
        assertEquals(1, categoryPriority("railway:station"))
        assertEquals(1, categoryPriority("public_transport:station"))
        assertEquals(2, categoryPriority("place:city"))
        assertEquals(3, categoryPriority("shop:convenience"))
        assertEquals(3, categoryPriority("tourism:attraction"))
        assertEquals(4, categoryPriority("amenity:toilets"))
        assertEquals(5, categoryPriority("highway:bus_stop"))
        assertEquals(5, categoryPriority("amenity:bus"))
    }

    @Test
    fun clusterBusStops_clustersNearbySameNameStops() {
        val bus1 = SearchResult("広島駅", "highway:bus_stop", 34.3970, 132.4750, 100.0)
        val bus2 = SearchResult("広島駅", "highway:bus_stop", 34.3972, 132.4752, 120.0) // ~30m distance
        val bus3 = SearchResult("八丁堀", "highway:bus_stop", 34.3920, 132.4630, 1200.0)
        val station = SearchResult("広島", "railway:station", 34.3975, 132.4755, 90.0)

        val input = listOf(station, bus1, bus2, bus3)
        val clustered = clusterBusStops(input, clusterRadiusM = 300.0)

        // bus1 and bus2 should be clustered into 1 entry
        assertEquals(3, clustered.size)
        assertEquals(1, clustered.count { it.name == "広島駅" && it.category == "highway:bus_stop" })
        assertEquals(1, clustered.count { it.name == "八丁堀" })
        assertEquals(1, clustered.count { it.name == "広島" })
    }

    @Test
    fun rankSearchResults_prioritizesStationOverBusStop() {
        val bus = SearchResult("広島駅", "highway:bus_stop", 34.3970, 132.4750, 50.0)
        val station = SearchResult("広島", "railway:station", 34.3975, 132.4755, 100.0)
        val cafe = SearchResult("広島珈琲", "amenity:cafe", 34.3960, 132.4740, 80.0)

        val input = listOf(bus, cafe, station)
        val ranked = rankSearchResults(input, "広島駅", userLat = 34.390, userLon = 132.470)

        // When searching "広島駅", station "広島" matches stationBase and is railway:station, so it should be rank 1
        assertEquals("広島", ranked[0].name)
        assertEquals("railway:station", ranked[0].category)
    }

    @Test
    fun formatCategoryLabel_convertsOsmTagsToJapanese() {
        assertEquals("鉄道駅", formatCategoryLabel("railway:station"))
        assertEquals("バス停", formatCategoryLabel("highway:bus_stop"))
        assertEquals("コンビニ", formatCategoryLabel("shop:convenience"))
        assertEquals("自転車店", formatCategoryLabel("shop:bicycle"))
        assertEquals("パン屋", formatCategoryLabel("shop:bakery"))
        assertEquals("温泉・銭湯", formatCategoryLabel("amenity:public_bath"))
        assertEquals("トイレ", formatCategoryLabel("amenity:toilets"))
        assertEquals("カフェ", formatCategoryLabel("amenity:cafe"))
    }

    @Test
    fun searchPoisInBounds_returnsEmptyWhenFileDoesNotExist() {
        val nonExistent = File("/tmp/non_existent_pois.db")
        val result = searchPoisInBounds(nonExistent, 34.0, 132.0, 34.5, 132.5)
        assertEquals(emptyList<NearbySpot>(), result)
    }

    @Test
    fun dedupeNearbySameName_mergesStationNameWithAndWithoutEkiWithin50m() {
        // 座標差約 6m の「岩国」と「岩国駅」(両方とも駅カテゴリ) -> 先の方だけ残る
        val first = SearchResult("岩国駅", "railway:station", 34.17260, 132.22620, 25800.0)
        val second = SearchResult("岩国", "public_transport:station", 34.17265, 132.22622, 25806.0)

        val deduped = dedupeNearbySameName(listOf(first, second))
        assertEquals(1, deduped.size)
        assertEquals("岩国駅", deduped[0].name)

        // 逆順でも先の方 (ランク上位) を残す
        val reversed = dedupeNearbySameName(listOf(second, first))
        assertEquals(1, reversed.size)
        assertEquals("岩国", reversed[0].name)
    }

    @Test
    fun dedupeNearbySameName_doesNotMergeNonStationOrUnknownCategory() {
        val station = SearchResult("岩国駅", "railway:station", 34.17260, 132.22620, 100.0)
        val postOffice = SearchResult("岩国駅前郵便局", "amenity:post_office", 34.17275, 132.22625, 120.0) // ~20m
        val unknownCategory = SearchResult("岩国", "named", 34.17262, 132.22621, 102.0) // ~3m, カテゴリ不明

        val dedupedPost = dedupeNearbySameName(listOf(station, postOffice))
        assertEquals(2, dedupedPost.size)

        val dedupedUnknown = dedupeNearbySameName(listOf(station, unknownCategory))
        assertEquals(2, dedupedUnknown.size)
    }

    @Test
    fun dedupeNearbySameName_keepsSameNameStationsBeyondDistanceAndDistinctExits() {
        // 300m 離れた同名の駅 -> 2 件のまま
        val st1 = SearchResult("岩国駅", "railway:station", 34.17260, 132.22620, 100.0)
        val st2 = SearchResult("岩国駅", "railway:station", 34.17530, 132.22620, 400.0) // ~300m
        assertEquals(2, dedupeNearbySameName(listOf(st1, st2)).size)

        // 50m を超える「岩国」と「岩国駅」(~80m) -> 2 件のまま
        val stFar = SearchResult("岩国", "railway:station", 34.17332, 132.22620, 180.0)
        assertEquals(2, dedupeNearbySameName(listOf(st1, stFar)).size)

        // 東口・西口など名前が異なる出入口 -> 2 件のまま
        val east = SearchResult("岩国駅 東口", "railway:station", 34.17260, 132.22620, 100.0)
        val west = SearchResult("岩国駅 西口", "railway:station", 34.17262, 132.22621, 102.0)
        assertEquals(2, dedupeNearbySameName(listOf(east, west)).size)
    }

    @Test
    fun searchPoisInBounds_returnsEmptyForInvalidBounds() {
        val nonExistent = File("/tmp/non_existent_pois.db")
        // minLat >= maxLat
        assertEquals(emptyList<NearbySpot>(), searchPoisInBounds(nonExistent, 35.0, 132.0, 34.0, 132.5))
        // minLon >= maxLon
        assertEquals(emptyList<NearbySpot>(), searchPoisInBounds(nonExistent, 34.0, 133.0, 34.5, 132.0))
        // limit <= 0
        assertEquals(emptyList<NearbySpot>(), searchPoisInBounds(nonExistent, 34.0, 132.0, 34.5, 132.5, limit = 0))
    }
}
