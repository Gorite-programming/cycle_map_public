package com.gorite.cyclemap.ui.cycling

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationProfileChartTest {
    @Test
    fun totalDistanceAxisLabelKeepsUnitAndOneDecimal() {
        assertEquals("335.7km", formatElevationAxisDistance(335_730.0))
        assertEquals("170.0km", formatElevationAxisDistance(170_000.0))
    }
}
