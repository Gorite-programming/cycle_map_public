package com.gorite.cyclemap.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class NavPanelFormatTest {
    @Test
    fun remainingUsesMetersBelowOneKilometer() {
        assertEquals("850" to "m", splitRemaining(850.0))
        assertEquals("0" to "m", splitRemaining(-5.0))
    }

    @Test
    fun remainingKeepsOneDecimalBelowTenKilometers() {
        assertEquals("9.8" to "km", splitRemaining(9_840.0))
    }

    @Test
    fun remainingIsIntegerFromTenKilometersSoItFitsOnOneLine() {
        assertEquals("102" to "km", splitRemaining(102_057.0))
        assertEquals("10" to "km", splitRemaining(10_000.0))
    }

    @Test
    fun gradeShowsZeroWithoutSign() {
        assertEquals("0", formatGradePct(-0.2))
        assertEquals("0", formatGradePct(0.4))
    }

    @Test
    fun gradeKeepsSignForNonZeroValues() {
        assertEquals("+4", formatGradePct(3.6))
        assertEquals("-3", formatGradePct(-2.7))
    }

    @Test
    fun gradeUnknownIsDashes() {
        assertEquals("--", formatGradePct(null))
        assertEquals("--", formatGradePct(Double.NaN))
    }
}
