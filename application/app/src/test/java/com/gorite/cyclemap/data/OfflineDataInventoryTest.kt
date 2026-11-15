package com.gorite.cyclemap.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineDataInventoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val root: File get() = folder.root

    private fun file(name: String, bytes: Int): File = File(root, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Test fun emptyDirectoryHasNoInstalledPrefectures() {
        assertTrue(offlineDataStatus(root).isEmpty())
        assertTrue(prefectureDataStatus(root, "tokyo", "東京都").isEmpty)
    }

    @Test fun sumsAllResolvedKindsIncludingIndexButNotPortals() {
        file("Hiroshima.graph", 11)
        file("Hiroshima.graph.idx", 12)
        file("Hiroshima.search.db", 13)
        file("tiles/Hiroshima_osm.pmtiles", 14)
        file("tiles/hiroshima.contours.pmtiles", 15)
        file("tiles/hiroshima.hillshade.pmtiles", 16)
        file("hiroshima.graph.ch", 17)
        file("Hiroshima__Yamaguchi.portals", 100)
        val status = prefectureDataStatus(root, "hiroshima", "広島県")
        assertTrue(status.isComplete)
        assertTrue(status.hasTerrain)
        assertTrue(status.hasFastRouting)
        assertEquals(98L, status.totalBytes)
        assertEquals(listOf(status), offlineDataStatus(root))
    }

    @Test fun legacySearchAndLowercaseGraphAreResolved() {
        file("Yamaguchi.graph", 10)
        file("Yamaguchi.graph.idx", 20)
        file("search.db", 30)
        val status = prefectureDataStatus(root, "yamaguchi", "山口県")
        assertTrue(status.hasRouting)
        assertTrue(status.hasSearch)
        assertFalse(status.hasVectorMap)
        assertFalse(status.isComplete)
        assertEquals(60L, status.totalBytes)
    }

    @Test fun terrainOnlyAndHierarchyOnlyPrefecturesAreIncluded() {
        file("tiles/tokyo.contours.pmtiles", 25)
        file("osaka.graph.ch", 30)
        val status = offlineDataStatus(root)
        assertEquals(listOf("tokyo", "osaka"), status.map { it.id })
        assertTrue(status[0].hasTerrain)
        assertFalse(status[0].hasVectorMap)
        assertTrue(status[1].hasFastRouting)
        assertFalse(status[1].hasRouting)
        assertEquals(55L, status.sumOf { it.totalBytes })
    }

    @Test fun emptyTilesAndTerrainAreNotAvailable() {
        file("tokyo.pmtiles", 0)
        file("tokyo.hillshade.pmtiles", 0)
        assertTrue(offlineDataStatus(root).isEmpty())
    }

    @Test fun preferredFilesAreNotDoubleCountedWithFallbackCopies() {
        file("hiroshima.pmtiles", 10)
        file("tiles/hiroshima.pmtiles", 20)
        file("Hiroshima_osm.pmtiles", 30)
        assertEquals(10L, prefectureDataStatus(root, "hiroshima", "広島県").totalBytes)
    }

    @Test fun formatsBytesAndLargeSizesWithoutIntegerOverflow() {
        assertEquals("0 B", formatOfflineDataSize(0))
        assertEquals("1.2 KB", formatOfflineDataSize(1_200))
        assertEquals("54.4 MB", formatOfflineDataSize(54_400_000))
        assertEquals("3.2 GB", formatOfflineDataSize(3_200_000_000L))
    }
}
