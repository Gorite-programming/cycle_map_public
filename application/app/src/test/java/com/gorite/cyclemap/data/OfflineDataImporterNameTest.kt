package com.gorite.cyclemap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineDataImporterNameTest {
    @Test
    fun browserCopyNumbersAreRemovedFromSimpleAndCompoundExtensions() {
        assertEquals("Hiroshima.graph", OfflineDataImporter.targetName("Hiroshima (1).graph"))
        assertEquals("Yamaguchi.graph.idx", OfflineDataImporter.targetName("Yamaguchi.graph (1).idx"))
        assertEquals("Yamaguchi.search.db", OfflineDataImporter.targetName("Yamaguchi.search (2).db"))
        assertEquals("yamaguchi.contours.pmtiles", OfflineDataImporter.targetName("yamaguchi.contours (1).pmtiles"))
        assertEquals("Hiroshima.search.db", OfflineDataImporter.targetName("x/Hiroshima.search.db"))
    }

    @Test
    fun unknownNamesAreStillRejected() {
        assertNull(OfflineDataImporter.targetName("manifest.json"))
        // パスの区切りは落とされるので、フォルダの外へは書けない
        assertEquals("evil.graph", OfflineDataImporter.targetName("../evil.graph"))
        assertNull(OfflineDataImporter.targetName("a b.graph"))
    }
}
