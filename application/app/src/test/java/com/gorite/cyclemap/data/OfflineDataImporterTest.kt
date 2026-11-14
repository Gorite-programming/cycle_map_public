package com.gorite.cyclemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineDataImporterTest {
    @Test
    fun keepsKnownDataFilesAndStripsFoldersAndBrowserCopySuffixes() {
        assertEquals("Hiroshima.graph", OfflineDataImporter.targetName("Hiroshima.graph"))
        assertEquals("Hiroshima.graph.idx", OfflineDataImporter.targetName("work/Hiroshima.graph.idx"))
        assertEquals("Hiroshima.graph.ch", OfflineDataImporter.targetName("Hiroshima (1).graph.ch"))
        assertEquals("Hiroshima.search.db", OfflineDataImporter.targetName("Hiroshima-2.search.db"))
        assertEquals("hiroshima.pmtiles", OfflineDataImporter.targetName("hiroshima.pmtiles"))
        assertEquals("hiroshima.contours.pmtiles", OfflineDataImporter.targetName("hiroshima.contours.pmtiles"))
        assertEquals("Hiroshima__Okayama.portals", OfflineDataImporter.targetName("Hiroshima__Okayama.portals"))
    }

    @Test
    fun ignoresOtherFilesAndUnsafeNames() {
        assertNull(OfflineDataImporter.targetName("manifest.json"))
        assertNull(OfflineDataImporter.targetName("LICENSE.txt"))
        assertEquals("evil.graph", OfflineDataImporter.targetName("../evil.graph")) // フォルダ部分は捨てる
        assertNull(OfflineDataImporter.targetName(".graph"))
        assertNull(OfflineDataImporter.targetName("広島.graph"))
    }
}
