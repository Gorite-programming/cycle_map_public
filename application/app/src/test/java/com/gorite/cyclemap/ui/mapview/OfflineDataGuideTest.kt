package com.gorite.cyclemap.ui.mapview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDataGuideTest {
    private val folder = "/sdcard/Android/data/app/files/Documents/CycleMap"

    @Test
    fun supportedPrefecture_listsFourFilesAndSteps() {
        val guide = buildOfflineDataGuide("広島県", "hiroshima.pmtiles", folder)
        assertTrue(guide.hasInstallSteps)
        assertEquals(
            listOf("hiroshima.pmtiles", "Hiroshima.graph", "Hiroshima.graph.idx", "Hiroshima.search.db"),
            guide.requiredFiles,
        )
        assertEquals(3, guide.steps.size)
        assertTrue(guide.title.contains("広島県"))
        assertEquals(folder, guide.folderPath)
    }

    @Test
    fun unresolvedRegion_explainsScopeWithoutSteps() {
        val guide = buildOfflineDataGuide("沖合", null, folder)
        assertFalse(guide.hasInstallSteps)
        assertTrue(guide.requiredFiles.isEmpty())
        assertTrue(guide.summary.contains("沖合"))
        assertTrue(guide.summary.contains("47 都道府県"))
    }

    @Test
    fun unknownPrefecture_usesGenericLabel() {
        val guide = buildOfflineDataGuide(null, null, folder)
        assertTrue(guide.summary.startsWith("この地域は"))
    }
}
