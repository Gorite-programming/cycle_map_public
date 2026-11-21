package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.hazard.HazardAhead
import com.gorite.cyclemap.hazard.HazardKind
import org.junit.Assert.assertEquals
import org.junit.Test

class HazardChipTextTest {

    @Test
    fun hazardChipText_formatsCrossingAndBridges() {
        val crossing = HazardAhead(HazardKind.CROSSING, null, 300.0, inside = false, remainingMeters = 0.0)
        assertEquals("踏切 あと 300 m", hazardChipText(crossing))

        val namedBridge = HazardAhead(HazardKind.BRIDGE, "錦城橋", 800.0, inside = false, remainingMeters = 0.0)
        assertEquals("橋 (錦城橋) あと 800 m", hazardChipText(namedBridge))

        val unnamedBridge = HazardAhead(HazardKind.BRIDGE, null, 800.0, inside = false, remainingMeters = 0.0)
        assertEquals("橋 あと 800 m", hazardChipText(unnamedBridge))

        val blankNameBridge = HazardAhead(HazardKind.BRIDGE, "  ", 250.0, inside = false, remainingMeters = 0.0)
        assertEquals("橋 あと 250 m", hazardChipText(blankNameBridge))

        val insideNamed = HazardAhead(HazardKind.BRIDGE, "錦城橋", 0.0, inside = true, remainingMeters = 120.0)
        assertEquals("橋 (錦城橋) 残り 100 m", hazardChipText(insideNamed))

        val insideNamedExact = HazardAhead(HazardKind.BRIDGE, "錦城橋", 0.0, inside = true, remainingMeters = 126.0)
        assertEquals("橋 (錦城橋) 残り 150 m", hazardChipText(insideNamedExact))

        val insideUnnamed = HazardAhead(HazardKind.BRIDGE, null, 0.0, inside = true, remainingMeters = 84.0)
        assertEquals("橋 残り 80 m", hazardChipText(insideUnnamed))
    }

    @Test
    fun formatHazardDistance_roundsToExpectedSteps() {
        assertEquals("0 m", formatHazardDistance(-5.0))
        assertEquals("0 m", formatHazardDistance(0.0))
        assertEquals("0 m", formatHazardDistance(Double.NaN))
        assertEquals("0 m", formatHazardDistance(4.0))
        assertEquals("10 m", formatHazardDistance(5.0))
        assertEquals("90 m", formatHazardDistance(94.0))
        assertEquals("100 m", formatHazardDistance(95.0))
        assertEquals("100 m", formatHazardDistance(100.0))
        assertEquals("350 m", formatHazardDistance(349.0))
        assertEquals("1.0 km", formatHazardDistance(975.0))
        assertEquals("1.0 km", formatHazardDistance(1000.0))
        assertEquals("1.2 km", formatHazardDistance(1234.0))
    }

    @Test
    fun hazardChipContentDescription_usesSpokenUnits() {
        val crossing = HazardAhead(HazardKind.CROSSING, null, 300.0, inside = false, remainingMeters = 0.0)
        assertEquals("踏切、あと 300 メートル", hazardChipContentDescription(crossing))

        val bridgeKm = HazardAhead(HazardKind.BRIDGE, "錦帯橋", 1234.0, inside = false, remainingMeters = 0.0)
        assertEquals("橋 (錦帯橋)、あと 1.2 キロメートル", hazardChipContentDescription(bridgeKm))

        val insideBridge = HazardAhead(HazardKind.BRIDGE, null, 0.0, inside = true, remainingMeters = 90.0)
        assertEquals("橋、残り 90 メートル", hazardChipContentDescription(insideBridge))
    }
}
