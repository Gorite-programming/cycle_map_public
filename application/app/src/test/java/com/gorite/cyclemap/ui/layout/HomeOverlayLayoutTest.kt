package com.gorite.cyclemap.ui.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOverlayLayoutTest {
    @Test fun bannersMoveControlsAndReduceDashboardByTheirHeight() {
        assertEquals(100.dp, homeControlsTop(24.dp, 56.dp))
        assertEquals(180.dp, homeControlsTop(24.dp, 136.dp))
        assertEquals(300.dp, landscapeHomeMaxHeight(400.dp, 24.dp, 56.dp))
        assertEquals(220.dp, landscapeHomeMaxHeight(400.dp, 24.dp, 136.dp))
    }

    @Test fun oversizedOverlayLeavesNoDashboardSpace() {
        assertEquals(0.dp, landscapeHomeMaxHeight(200.dp, 24.dp, 300.dp))
    }
}
