package com.gorite.cyclemap.ui.cycling
import org.junit.Assert.*
import org.junit.Test

class CycleComputerLayoutTest {
    @Test fun shortScreensAndLargeFontsReduceSpeedSize() {
        val regular = cycleComputerSpeedFontSize(780f, 1f, false)
        assertEquals(124f, regular, 0f)
        assertTrue(cycleComputerSpeedFontSize(480f, 1f, false) < regular)
        assertTrue(cycleComputerSpeedFontSize(480f, 2f, false) < cycleComputerSpeedFontSize(480f, 1f, false))
        assertTrue(cycleComputerSpeedFontSize(300f, 2f, true) < cycleComputerSpeedFontSize(300f, 1f, true))
    }
    @Test fun speedSizeIsBoundedEvenWithInvalidConstraints() {
        assertEquals(36f, cycleComputerSpeedFontSize(0f, 2f, false), 0f)
        assertEquals(124f, cycleComputerSpeedFontSize(2000f, 1f, true), 0f)
        assertEquals(36f, cycleComputerSpeedFontSize(Float.NaN, Float.NaN, false), 0f)
        assertTrue(cycleComputerSpeedFontSize(400f, 0f, true).isFinite())
    }
}
