package com.gorite.cyclemap.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossPrefectureNoticeTest {
    @Test
    fun differentPrefecturesOver50Km_showsBothNotices() {
        assertEquals(
            CrossPrefectureNotice(showLongCalculationNotice = true, showUnsupportedWarning = true),
            crossPrefectureNotice("広島県", "岡山県", 50_000.1),
        )
    }

    @Test
    fun exactly50Km_doesNotShowLongCalculationNotice() {
        assertEquals(
            CrossPrefectureNotice(showLongCalculationNotice = false, showUnsupportedWarning = true),
            crossPrefectureNotice("広島県", "岡山県", 50_000.0),
        )
    }

    @Test
    fun shortCrossPrefectureRoute_showsOnlyUnsupportedWarning() {
        assertEquals(
            CrossPrefectureNotice(showLongCalculationNotice = false, showUnsupportedWarning = true),
            crossPrefectureNotice("広島県", "岡山県", 12_000.0),
        )
    }

    @Test
    fun samePrefecture_showsNeitherNotice() {
        assertEquals(
            CrossPrefectureNotice(showLongCalculationNotice = false, showUnsupportedWarning = false),
            crossPrefectureNotice("広島県", "広島県", 80_000.0),
        )
    }

    @Test
    fun unknownPrefecture_showsNeitherNotice() {
        assertEquals(
            CrossPrefectureNotice(showLongCalculationNotice = false, showUnsupportedWarning = false),
            crossPrefectureNotice(null, "岡山県", 80_000.0),
        )
        assertEquals(
            CrossPrefectureNotice(showLongCalculationNotice = false, showUnsupportedWarning = false),
            crossPrefectureNotice("広島県", null, 80_000.0),
        )
    }
}
