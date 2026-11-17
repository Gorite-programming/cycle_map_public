package com.gorite.cyclemap.search

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MultiPrefectureSearchTest {
    private fun place(name: String, lon: Double, pref: String) =
        SearchResult(name, "shop:convenience", 34.0, lon, prefectureName = pref)

    @Test fun mergesRelevanceAcrossDatabasesAndDeduplicatesBorderPlaces() {
        val first = listOf(place("駅前店", 131.0, "山口県"), place("駅前店", 131.00001, "山口県"))
        val second = listOf(place("駅", 132.0, "広島県"))
        val merged = mergeSearchResults(listOf(first, second), "駅", 34.0, 131.0)
        assertEquals(listOf("駅", "駅前店"), merged.map { it.name })
        assertEquals("広島県", merged.first().prefectureName)
        assertEquals(rankSearchResults(first + second, "駅", 34.0, 131.0), merged)
    }

    @Test fun categoryResultsUseGlobalDistanceOrder() {
        val distant = place("遠い店", 132.0, "広島県")
        val near = place("近い店", 131.0001, "山口県")
        val result = mergeSearchResults(listOf(listOf(distant), listOf(near)), "", 34.0, 131.0)
        assertEquals(listOf("近い店", "遠い店"), result.map { it.name })
    }

    @Test fun selectsInstalledNeighboursEvenWhenCurrentDatabaseIsMissing() {
        val dir = Files.createTempDirectory("search-neighbours").toFile()
        try {
            listOf("Hiroshima.search.db", "Shimane.search.db", "Tokyo.search.db").forEach { File(dir, it).writeText("") }
            val sources = availableSearchDatabases(dir, File(dir, "Yamaguchi.search.db"))
            assertEquals(setOf("広島県", "島根県"), sources.map { it.prefectureName }.toSet())
            assertEquals(2, sources.size)
        } finally { dir.deleteRecursively() }
    }

    @Test fun legacyPrimaryDatabaseIsIncludedOnce() {
        val dir = Files.createTempDirectory("search-legacy").toFile()
        try {
            val primary = File(dir, "search.db").apply { writeText("") }
            val sources = availableSearchDatabases(dir, primary)
            assertEquals(listOf(SearchDatabase(primary, "山口県")), sources)
        } finally { dir.deleteRecursively() }
    }
}
