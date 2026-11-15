package com.gorite.cyclemap.ui.home

import com.gorite.cyclemap.data.RoutingGraphSelection
import com.gorite.cyclemap.data.RoutingGraphSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HomeOfflineStatusTest {

    @Test
    fun shortPrefectureName_stripsAdministrativeSuffixExceptHokkaido() {
        assertEquals("広島", shortPrefectureName("広島県"))
        assertEquals("東京", shortPrefectureName("東京都"))
        assertEquals("大阪", shortPrefectureName("大阪府"))
        assertEquals("京都", shortPrefectureName("京都府"))
        assertEquals("北海道", shortPrefectureName("北海道"))
        assertEquals("山口", shortPrefectureName("山口"))
    }

    @Test
    fun resolvePrefectureAtLocation_handlesNullInvalidAndKnownCoords() {
        assertNull(resolvePrefectureAtLocation(null, 132.4756))
        assertNull(resolvePrefectureAtLocation(34.3976, null))
        assertNull(resolvePrefectureAtLocation(Double.NaN, 132.4756))
        assertNull(resolvePrefectureAtLocation(34.3976, Double.POSITIVE_INFINITY))

        val hiroshima = resolvePrefectureAtLocation(34.3976, 132.4756)
        assertTrue(hiroshima is RoutingGraphSelection.Available)
        hiroshima as RoutingGraphSelection.Available
        assertEquals("hiroshima", hiroshima.prefectureId)
        assertEquals("広島県", hiroshima.prefectureName)
        assertEquals("Hiroshima.graph", hiroshima.fileName)

        val outside = resolvePrefectureAtLocation(30.0, 135.0)
        assertTrue(outside is RoutingGraphSelection.Unavailable)
        assertNull((outside as RoutingGraphSelection.Unavailable).prefectureName)
    }

    @Test
    fun isLoadedGraphForSelection_matchesIgnoringCase() {
        val yamaguchi = RoutingGraphSelection.Available("yamaguchi", "山口県", "yamaguchi.graph")
        val hiroshima = RoutingGraphSelection.Available("hiroshima", "広島県", "Hiroshima.graph")

        assertTrue(isLoadedGraphForSelection(yamaguchi, "yamaguchi.graph"))
        assertTrue(isLoadedGraphForSelection(yamaguchi, "Yamaguchi.graph"))
        assertFalse(isLoadedGraphForSelection(hiroshima, "Yamaguchi.graph"))
        assertFalse(isLoadedGraphForSelection(hiroshima, null))
    }

    @Test
    fun buildHomeOfflineStatus_returnsExpectedLabelAndTone() {
        assertEquals(
            HomeOfflineStatus("現在地を取得中", HomeOfflineTone.NEUTRAL),
            buildHomeOfflineStatus(selection = null, hasGraphOnDevice = null, loadedGraphName = null),
        )

        val hiroshima = RoutingGraphSelection.Available("hiroshima", "広島県", "Hiroshima.graph")
        assertEquals(
            HomeOfflineStatus("広島 ・ オフラインで使えます", HomeOfflineTone.READY),
            buildHomeOfflineStatus(selection = hiroshima, hasGraphOnDevice = true, loadedGraphName = null),
        )
        assertEquals(
            HomeOfflineStatus("広島 ・ オフラインで使えます", HomeOfflineTone.READY),
            buildHomeOfflineStatus(selection = hiroshima, hasGraphOnDevice = false, loadedGraphName = "Hiroshima.graph"),
        )
        assertEquals(
            HomeOfflineStatus("広島 ・ ルートデータなし", HomeOfflineTone.WARNING),
            buildHomeOfflineStatus(selection = hiroshima, hasGraphOnDevice = false, loadedGraphName = "yamaguchi.graph"),
        )
        assertEquals(
            HomeOfflineStatus("広島 ・ 確認中", HomeOfflineTone.NEUTRAL),
            buildHomeOfflineStatus(selection = hiroshima, hasGraphOnDevice = null, loadedGraphName = null),
        )

        val yamaguchi = RoutingGraphSelection.Available("yamaguchi", "山口県", "yamaguchi.graph")
        assertEquals(
            HomeOfflineStatus("山口 ・ オフラインで使えます", HomeOfflineTone.READY),
            buildHomeOfflineStatus(selection = yamaguchi, hasGraphOnDevice = null, loadedGraphName = "Yamaguchi.graph"),
        )

        assertEquals(
            HomeOfflineStatus("ルートデータなし", HomeOfflineTone.WARNING),
            buildHomeOfflineStatus(
                selection = RoutingGraphSelection.Unavailable(null),
                hasGraphOnDevice = null,
                loadedGraphName = null,
            ),
        )
        assertEquals(
            HomeOfflineStatus("架空 ・ ルートデータなし", HomeOfflineTone.WARNING),
            buildHomeOfflineStatus(
                selection = RoutingGraphSelection.Unavailable("架空県"),
                hasGraphOnDevice = null,
                loadedGraphName = null,
            ),
        )
    }

    @Test
    fun buildHomeOfflineStatus_integratesWithResolveGraphFiles() {
        val tempDir = Files.createTempDirectory("home_offline_status_test").toFile()
        try {
            val hiroshima = resolvePrefectureAtLocation(34.3976, 132.4756) as RoutingGraphSelection.Available
            // .graph のみで .idx が無いときはデータなし
            File(tempDir, "Hiroshima.graph").createNewFile()
            val missingIdx = RoutingGraphSelector.resolveGraphFiles(tempDir, hiroshima.fileName) != null
            assertEquals(
                HomeOfflineStatus("広島 ・ ルートデータなし", HomeOfflineTone.WARNING),
                buildHomeOfflineStatus(hiroshima, missingIdx, loadedGraphName = null),
            )

            // .graph + .graph.idx のペアが揃えばオフライン利用可
            File(tempDir, "Hiroshima.graph.idx").createNewFile()
            val bothPresent = RoutingGraphSelector.resolveGraphFiles(tempDir, hiroshima.fileName) != null
            assertEquals(
                HomeOfflineStatus("広島 ・ オフラインで使えます", HomeOfflineTone.READY),
                buildHomeOfflineStatus(hiroshima, bothPresent, loadedGraphName = null),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
