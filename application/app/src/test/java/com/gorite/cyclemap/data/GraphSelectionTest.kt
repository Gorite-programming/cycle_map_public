package com.gorite.cyclemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 県別グラフ選択 (RoutingGraphSelector) の検証。
 * 座標は実測値 (広島駅・山口市役所・札幌駅)。他県グラフの使い回しはしない。
 */
class GraphSelectionTest {

    @Test
    fun hiroshimaStation_selectsHiroshimaGraph() {
        val sel = RoutingGraphSelector.select(34.3976, 132.4756)
        assertTrue(sel is RoutingGraphSelection.Available)
        sel as RoutingGraphSelection.Available
        assertEquals("hiroshima", sel.prefectureId)
        assertEquals("広島県", sel.prefectureName)
        assertEquals("Hiroshima.graph", sel.fileName)
        assertEquals("Hiroshima.graph.idx", RoutingGraphSelector.indexFileName(sel.fileName))
    }

    @Test
    fun yamaguchiCityHall_selectsYamaguchiGraph() {
        val sel = RoutingGraphSelector.select(34.1785, 131.4737)
        assertTrue(sel is RoutingGraphSelection.Available)
        sel as RoutingGraphSelection.Available
        assertEquals("yamaguchi", sel.prefectureId)
        assertEquals("山口県", sel.prefectureName)
        assertEquals("yamaguchi.graph", sel.fileName)
    }

    @Test
    fun everyPrefecture_hasGraphFileName() {
        // 47 県すべてが <Romaji>.graph を引く (山口だけ既配備の yamaguchi.graph)。実在は resolveGraphFiles で確かめる。
        assertEquals(47, RoutingGraphSelector.SUPPORTED_FILES.size)
        PrefectureData.ALL.forEach { pref ->
            val expected = if (pref.id == "yamaguchi") "yamaguchi.graph" else pref.id.replaceFirstChar { it.uppercase() } + ".graph"
            assertEquals(expected, RoutingGraphSelector.SUPPORTED_FILES[pref.id])
        }
        assertEquals("Hyogo.graph", RoutingGraphSelector.graphFileNameForPrefectureName("兵庫県"))
        assertNull(RoutingGraphSelector.graphFileNameForPrefectureName("火星県"))
    }

    @Test
    fun sapporo_selectsHokkaidoGraphName_evenWithoutFileOnDevice() {
        // 札幌駅: 県は判定でき、ファイル名も決まる。端末に無いときの扱いは呼び出し側 (resolveGraphFiles が null)。
        val sel = RoutingGraphSelector.select(43.0687, 141.3508)
        assertTrue(sel is RoutingGraphSelection.Available)
        assertEquals("Hokkaido.graph", (sel as RoutingGraphSelection.Available).fileName)
        val empty = java.nio.file.Files.createTempDirectory("graph_empty").toFile()
        try {
            assertNull(RoutingGraphSelector.resolveGraphFiles(empty, sel.fileName))
        } finally {
            empty.deleteRecursively()
        }
    }

    @Test
    fun outsideJapan_isUnavailableWithNull() {
        val sel = RoutingGraphSelector.select(30.0, 135.0)
        assertTrue(sel is RoutingGraphSelection.Unavailable)
        assertNull((sel as RoutingGraphSelection.Unavailable).prefectureName)
    }

    @Test
    fun findFirstAvailableGraph_testCases() {
        val tempDir = java.nio.file.Files.createTempDirectory("graph_test").toFile()
        try {
            // 1. 空のディレクトリ → null
            assertNull(RoutingGraphSelector.findFirstAvailableGraph(tempDir))

            // 2. .idx が欠けているグラフ (B.graph) は無視し、有効なペア (C.graph) が返る
            java.io.File(tempDir, "B.graph").createNewFile()
            val cGraph = java.io.File(tempDir, "C.graph").apply { createNewFile() }
            val cIdx = java.io.File(tempDir, "C.graph.idx").apply { createNewFile() }

            val res1 = RoutingGraphSelector.findFirstAvailableGraph(tempDir)
            assertEquals(cGraph, res1?.first)
            assertEquals(cIdx, res1?.second)

            // 3. 昇順で最先頭のペア (A.graph) を追加すると A.graph のペアが返る
            val aGraph = java.io.File(tempDir, "A.graph").apply { createNewFile() }
            val aIdx = java.io.File(tempDir, "A.graph.idx").apply { createNewFile() }

            val res2 = RoutingGraphSelector.findFirstAvailableGraph(tempDir)
            assertEquals(aGraph, res2?.first)
            assertEquals(aIdx, res2?.second)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
