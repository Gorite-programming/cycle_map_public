package com.gorite.cyclemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryManagerTest {
    private fun place(name: String = "広島駅", lat: Double = 34.397, lon: Double = 132.475) =
        SearchHistoryEntry(name, "railway:station", lat, lon, "広島県広島市")

    @Test
    fun addPrependsWithoutChangingInput() {
        val old = listOf(place("前の行き先"))
        val entry = place()
        assertEquals(listOf(entry, old.single()), SearchHistoryStore.add(old, entry))
        assertEquals(listOf(place("前の行き先")), old)
    }

    @Test
    fun duplicateMovesToFrontAndRefreshesDetails() {
        val original = place()
        val other = place("別の行き先")
        val updated = original.copy(latitude = original.latitude + 0.00009, address = "更新された住所")
        assertEquals(listOf(updated, other), SearchHistoryStore.add(listOf(other, original), updated))
    }

    @Test
    fun duplicateIncludesToleranceBoundaryOnBothCoordinates() {
        val original = place()
        val boundary = original.copy(latitude = original.latitude + 0.0001, longitude = original.longitude - 0.0001)
        assertEquals(listOf(boundary), SearchHistoryStore.add(listOf(original), boundary))
    }

    @Test
    fun differentNameOrEitherCoordinateOutsideToleranceIsKept() {
        val original = place()
        listOf(
            original.copy(name = "別の名前"),
            original.copy(latitude = original.latitude + 0.00011),
            original.copy(longitude = original.longitude - 0.00011),
        ).forEach { other ->
            assertEquals(listOf(other, original), SearchHistoryStore.add(listOf(original), other))
        }
    }

    @Test
    fun keepsOnlyTenMostRecentEntries() {
        val history = (0..14).fold(emptyList<SearchHistoryEntry>()) { entries, i ->
            SearchHistoryStore.add(entries, place("行き先$i"))
        }
        assertEquals((14 downTo 5).map { place("行き先$it") }, history)
    }

    @Test
    fun removeDeletesOnlyMatchingPlace() {
        val original = place()
        val other = place("同じ位置の別施設")
        assertEquals(listOf(other), SearchHistoryStore.remove(listOf(original, other), original.copy(latitude = original.latitude + 0.00005)))
        assertEquals(listOf(other), SearchHistoryStore.remove(listOf(other), original))
        assertTrue(SearchHistoryStore.remove(listOf(original), original).isEmpty())
    }

    @Test
    fun jsonRoundTripPreservesOrderFieldsAndEscapes() {
        val entries = listOf(
            place("駅\"前\\店\n日本語").copy(address = "住所\t1"),
            place("住所なし").copy(address = null),
            place("空住所").copy(address = ""),
        )
        assertEquals(entries, SearchHistoryStore.fromJson(SearchHistoryStore.toJson(entries)))
        assertEquals("[]", SearchHistoryStore.toJson(emptyList()))
        assertTrue(SearchHistoryStore.fromJson("[]").isEmpty())
    }

    @Test
    fun invalidJsonReturnsEmptyHistory() {
        listOf(null, "", "broken", "{}", "null").forEach {
            assertTrue(SearchHistoryStore.fromJson(it).isEmpty())
        }
    }

    @Test
    fun invalidRowsAreSkippedAndNewestDuplicateWins() {
        val entry = place()
        val row = SearchHistoryStore.toJson(listOf(entry)).removePrefix("[").removeSuffix("]")
        val invalid = "{\"name\":\"bad\",\"category\":\"spot\",\"lat\":91,\"lon\":132}"
        assertEquals(listOf(entry), SearchHistoryStore.fromJson("[null,{},$invalid,$row,$row]"))
    }

    @Test
    fun jsonReadAndWriteAreLimitedToTen() {
        val entries = (0..14).map { place("行き先$it") }
        assertEquals(entries.take(10), SearchHistoryStore.fromJson(SearchHistoryStore.toJson(entries)))
        val unlimitedJson = entries.joinToString(prefix = "[", postfix = "]") {
            SearchHistoryStore.toJson(listOf(it)).removePrefix("[").removeSuffix("]")
        }
        assertEquals(entries.take(10), SearchHistoryStore.fromJson(unlimitedJson))
    }
}
