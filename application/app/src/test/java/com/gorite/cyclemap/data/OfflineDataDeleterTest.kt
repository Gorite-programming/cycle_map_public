package com.gorite.cyclemap.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineDataDeleterTest {
    @get:Rule val folder = TemporaryFolder()
    private val root: File get() = folder.root
    private val hiroshima get() = PrefectureData.ALL.first { it.id == "hiroshima" }
    private val yamaguchi get() = PrefectureData.ALL.first { it.id == "yamaguchi" }

    private fun file(name: String, bytes: Int = 10): File = File(root, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(bytes))
    }

    @Test fun planListsOnlyExactPrefectureFilesAndItsPortalsAndSumsBytes() {
        val expected = listOf(
            "Hiroshima.graph", "Hiroshima.graph.idx", "Hiroshima.search.db",
            "hiroshima.pmtiles", "hiroshima.contours.pmtiles", "hiroshima.hillshade.pmtiles",
            "Hiroshima.graph.ch", "Hiroshima__Yamaguchi.portals", "Fukuoka__Hiroshima.portals",
        )
        expected.forEachIndexed { i, name -> file(name, i + 1) }
        file("Okayama__Yamaguchi.portals")
        file("HiroshimaExtra__Yamaguchi.portals")
        file("Hiroshima__Unknown.portals")
        val plan = OfflineDataDeleter.plan(root, hiroshima)
        assertEquals(expected.map { File(root, it) }.toSet(), plan.files.toSet())
        assertEquals(45L, plan.totalBytes)
        assertEquals(8, plan.kinds.size)
    }

    @Test fun deletionPreservesOtherPrefecturesBackupsSimilarNamesAndSubdirectories() {
        val deleted = listOf("Hiroshima.graph", "Hiroshima.graph.idx", "Hiroshima.search.db",
            "Hiroshima_osm.pmtiles", "hiroshima.hillshade.pmtiles", "Hiroshima__Yamaguchi.portals")
            .map { file(it) }
        val retained = listOf(
            "Yamaguchi.graph", "Yamaguchi.search.db", "yamaguchi.pmtiles",
            "Hiroshima.graph.bak", "Hiroshima.graph.ch.bak", "Hiroshima2.graph",
            "hiroshima.pmtiles.backup", "Hiroshima__Yamaguchi.portals.bak",
            "Okayama__Yamaguchi.portals", "gpx/Hiroshima.graph", "gpx/ride.gpx",
            "tiles/hiroshima.pmtiles", "tiles/hiroshima.contours.pmtiles",
            "benchmarks/Hiroshima.graph", "backup/Hiroshima.graph",
        ).map { file(it) }
        assertTrue(OfflineDataDeleter.delete(OfflineDataDeleter.plan(root, hiroshima)).isSuccess)
        deleted.forEach { assertFalse(it.path, it.exists()) }
        retained.forEach { assertTrue(it.path, it.isFile) }
    }

    @Test fun legacyNamesAndPartialGraphFilesAreIncluded() {
        val expected = listOf("Yamaguchi.graph.idx", "search.db", "Yamaguchi.search.db",
            "Yamaguchi_osm.pmtiles", "yamaguchi.graph.ch").map { file(it, 7) }
        val plan = OfflineDataDeleter.plan(root, yamaguchi)
        assertEquals(expected.toSet(), plan.files.toSet())
        assertEquals(35L, plan.totalBytes)
    }

    @Test fun absentFilesAndRepeatedDeletionSucceed() {
        assertTrue(OfflineDataDeleter.delete(OfflineDataDeleter.plan(root, hiroshima)).isSuccess)
        val graph = file("Hiroshima.graph")
        val plan = OfflineDataDeleter.plan(root, hiroshima)
        assertTrue(graph.delete())
        assertTrue(OfflineDataDeleter.delete(plan).isSuccess)
        assertTrue(OfflineDataDeleter.delete(plan).isSuccess)
        assertEquals(0L, OfflineDataDeleter.plan(root, hiroshima).totalBytes)
    }

    @Test fun usageRefusesGraphSearchTilesAndTerrainIncludingLegacyNames() {
        val graph = file("Hiroshima.graph")
        val plan = OfflineDataDeleter.plan(root, hiroshima)
        val usages = listOf(
            OfflineDataDeleter.Usage(loadedGraphName = "hiroshima.graph"),
            OfflineDataDeleter.Usage(searchDbName = "Hiroshima.search.db"),
            OfflineDataDeleter.Usage(searchDbNames = setOf("Hiroshima.search.db")),
            OfflineDataDeleter.Usage(tileNames = setOf("tiles/Hiroshima_osm.pmtiles")),
            OfflineDataDeleter.Usage(tileNames = setOf("hiroshima.contours.pmtiles")),
            OfflineDataDeleter.Usage(busy = true),
        )
        usages.forEach { usage ->
            val result = OfflineDataDeleter.delete(plan, usage)
            assertFalse(result.isSuccess)
            assertEquals(OfflineDataDeleter.IN_USE_MESSAGE, result.refusalReason)
            assertTrue(graph.exists())
        }
        assertNotNull(OfflineDataDeleter.refusalReason(
            OfflineDataDeleter.plan(root, yamaguchi), OfflineDataDeleter.Usage(searchDbName = "search.db")))
    }

    @Test fun otherPrefectureUsageDoesNotBlockDeletion() {
        file("Hiroshima.graph")
        val usage = OfflineDataDeleter.Usage(loadedGraphName = "Yamaguchi.graph",
            searchDbName = "search.db", tileNames = setOf("yamaguchi.pmtiles"))
        assertTrue(OfflineDataDeleter.delete(OfflineDataDeleter.plan(root, hiroshima), usage).isSuccess)
    }

    @Test fun aDirectoryReplacingAPlannedFileIsReportedAndNeverDeleted() {
        val blocked = file("Hiroshima.graph")
        val removable = file("Hiroshima.search.db")
        val plan = OfflineDataDeleter.plan(root, hiroshima)
        assertTrue(blocked.delete())
        assertTrue(blocked.mkdir())
        val child = file("Hiroshima.graph/keep")
        val result = OfflineDataDeleter.delete(plan)
        assertEquals(listOf(blocked), result.failed)
        assertFalse(result.isSuccess)
        assertTrue(child.isFile)
        assertFalse(removable.exists())
    }

    @Test fun unrelatedAndEmptyDirectoriesAreNeverPlanned() {
        File(root, "Hiroshima.graph").mkdir()
        File(root, "hiroshima.pmtiles").mkdir()
        assertTrue(OfflineDataDeleter.plan(root, hiroshima).files.isEmpty())
    }

    @Test fun zeroBytePrefectureFilesCanBeRemoved() {
        val tile = file("hiroshima.pmtiles", 0)
        val plan = OfflineDataDeleter.plan(root, hiroshima)
        assertEquals(listOf(tile), plan.files)
        assertEquals(0L, plan.totalBytes)
        assertTrue(OfflineDataDeleter.delete(plan).isSuccess)
        assertFalse(tile.exists())
    }
}
