package com.gorite.cyclemap.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineReadinessTest {
    @get:Rule val folder = TemporaryFolder()
    private val yamaguchi = ReadinessPoint(34.1859, 131.4714)
    private val hiroshima = ReadinessPoint(34.3853, 132.4553)
    private fun files(vararg names: String) = names.map { ReadinessFile(it, 100) }
    private val complete = files("yamaguchi.graph", "yamaguchi.graph.idx", "search.db", "yamaguchi.pmtiles")

    @Test fun allRequiredDataIsReadyAndOptionalDataMayBeAbsent() {
        val result = diagnoseOfflineReadiness(listOf(yamaguchi, yamaguchi), complete)
        assertTrue(result.isReady)
        assertEquals(1, result.prefectures.size)
        assertTrue(result.prefectures.single().items.filter { it.optional }.all { it.state == ReadinessState.MISSING })
    }

    @Test fun missingIndexMakesRoutingUnavailable() {
        val result = diagnoseOfflineReadiness(listOf(yamaguchi), complete.filterNot { it.path.endsWith(".idx") })
        assertFalse(result.isReady)
        assertEquals(ReadinessState.MISSING, result.prefectures.single().items.single { it.label == "経路データ" }.state)
    }

    @Test fun zeroByteFilesAreSuspiciousForEveryKind() {
        val names = complete + files("yamaguchi.graph.ch", "yamaguchi.contours.pmtiles", "yamaguchi.hillshade.pmtiles")
        val result = diagnoseOfflineReadiness(listOf(yamaguchi), names.map { it.copy(bytes = 0) })
        assertFalse(result.isReady)
        assertTrue(result.prefectures.single().items.all { it.state == ReadinessState.EMPTY })
    }

    @Test fun crossPrefecturePortalsAreOptionalAndUseResolvedGraphCase() {
        val data = complete + files("Hiroshima.graph", "Hiroshima.graph.idx", "Hiroshima.search.db", "hiroshima.pmtiles", "Hiroshima__yamaguchi.portals")
        val result = diagnoseOfflineReadiness(listOf(yamaguchi, hiroshima, yamaguchi), data)
        assertTrue(result.isReady)
        assertEquals(2, result.prefectures.size)
        assertEquals(ReadinessState.AVAILABLE, result.portals.single().state)
        assertTrue(result.portals.single().optional)
        assertEquals(ReadinessState.AVAILABLE, diagnoseOfflineReadiness(listOf(yamaguchi, hiroshima), data.map { if (it.path.endsWith(".portals")) it.copy(path = it.path.lowercase()) else it }).portals.single().state)
        assertEquals(ReadinessState.MISSING, diagnoseOfflineReadiness(listOf(yamaguchi, hiroshima), data.filterNot { it.path.endsWith(".portals") }).portals.single().state)
        assertEquals(ReadinessState.EMPTY, diagnoseOfflineReadiness(listOf(yamaguchi, hiroshima), data.map { if (it.path.endsWith(".portals")) it.copy(bytes = 0) else it }).portals.single().state)
    }

    @Test fun unknownAndEmptyPointsNeverClaimReady() {
        val result = diagnoseOfflineReadiness(listOf(ReadinessPoint(0.0, 0.0), ReadinessPoint(Double.NaN, 131.0), yamaguchi), complete)
        assertEquals(2, result.unknownPointCount)
        assertFalse(result.isReady)
        assertFalse(diagnoseOfflineReadiness(emptyList(), complete).isReady)
    }

    @Test fun legacyTilesAndTerrainAndSearchNamesMatchSelectors() {
        val data = files("Hiroshima.graph", "Hiroshima.graph.idx", "hiroshima.search.db", "tiles/Hiroshima_osm.pmtiles", "tiles/hiroshima.contours.pmtiles", "tiles/hiroshima.hillshade.pmtiles", "hiroshima.graph.ch")
        val result = diagnoseOfflineReadiness(listOf(hiroshima), data)
        assertTrue(result.isReady)
        assertTrue(result.prefectures.single().items.all { it.state == ReadinessState.AVAILABLE })
        assertTrue(diagnoseOfflineReadiness(listOf(yamaguchi), files("Yamaguchi.graph", "Yamaguchi.graph.idx", "Yamaguchi.search.db", "yamaguchi.pmtiles")).isReady)
    }

    @Test fun preferredEmptyGraphAndSearchAreNotHiddenByFallbacks() {
        val data = complete.map { if (it.path == "search.db" || it.path == "yamaguchi.graph") it.copy(bytes = 0) else it } +
            files("Yamaguchi.graph", "Yamaguchi.graph.idx", "Yamaguchi.search.db")
        val result = diagnoseOfflineReadiness(listOf(yamaguchi), data)
        assertEquals(2, result.prefectures.single().items.count { it.state == ReadinessState.EMPTY })
    }

    @Test fun fileInventoryReadsOnlyRootAndTilesFilesAndRetainsZeroBytes() {
        File(folder.root, "yamaguchi.graph").writeBytes(byteArrayOf(1))
        File(folder.root, "tiles").mkdir()
        File(folder.root, "tiles/yamaguchi.pmtiles").writeBytes(byteArrayOf())
        File(folder.root, "ignored").mkdir()
        File(folder.root, "ignored/search.db").writeBytes(byteArrayOf(2))
        assertEquals(setOf(ReadinessFile("yamaguchi.graph", 1), ReadinessFile("tiles/yamaguchi.pmtiles", 0)), readinessFileInventory(folder.root).toSet())
    }
}
