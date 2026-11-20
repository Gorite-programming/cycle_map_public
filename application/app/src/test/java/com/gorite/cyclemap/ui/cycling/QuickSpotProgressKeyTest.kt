package com.gorite.cyclemap.ui.cycling

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSpotProgressKeyTest {
    @Test fun updatesOnlyWhenCrossingTwoHundredMeterBuckets() {
        assertEquals(0L, quickSpotProgressKey(0.0))
        assertEquals(0L, quickSpotProgressKey(199.99))
        assertEquals(1L, quickSpotProgressKey(200.0))
        assertEquals(1L, quickSpotProgressKey(399.99))
        assertEquals(2L, quickSpotProgressKey(400.0))
        assertEquals(1L, quickSpotProgressKey(210.0)) // Backtracking also updates the remaining route.
    }

    @Test fun missingOrInvalidProgressUsesTheFullRouteBucket() {
        for (value in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0L, quickSpotProgressKey(value))
        }
    }
}
