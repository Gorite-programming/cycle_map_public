package com.gorite.cyclemap

import com.gorite.cyclemap.data.PrefectureDataStatus
import com.gorite.cyclemap.ui.components.offlineDataSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDataSummaryTest {
    @Test fun summaryIncludesOnlyInstalledComponents() {
        val base = PrefectureDataStatus("Yamaguchi", "山口", true, true, true)
        assertEquals("ルート・検索・地図が入っています", offlineDataSummary(base))
        assertEquals("ルート・検索・地図・地形・高速探索が入っています",
            offlineDataSummary(base.copy(hasTerrain = true, hasFastRouting = true)))
        assertEquals("ルート・検索・地図・高速探索が入っています",
            offlineDataSummary(base.copy(hasFastRouting = true)))
    }
}
