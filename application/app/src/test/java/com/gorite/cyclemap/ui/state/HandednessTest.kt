package com.gorite.cyclemap.ui.state

import androidx.compose.ui.Alignment
import org.junit.Assert.assertEquals
import org.junit.Test

class HandednessTest {
    @Test
    fun prefFallsBackToRight() {
        assertEquals(Handedness.LEFT, handednessFromPref("LEFT"))
        assertEquals(Handedness.RIGHT, handednessFromPref("RIGHT"))
        assertEquals(Handedness.RIGHT, handednessFromPref(null))
        assertEquals(Handedness.RIGHT, handednessFromPref("garbage"))
    }

    @Test
    fun leftHandMovesControlsToTheStartSide() {
        assertEquals(Alignment.TopEnd, controlsTopAlignment(false))
        assertEquals(Alignment.TopStart, controlsTopAlignment(true))
        assertEquals(Alignment.BottomEnd, controlsBottomAlignment(false))
        assertEquals(Alignment.BottomStart, controlsBottomAlignment(true))
    }
}
