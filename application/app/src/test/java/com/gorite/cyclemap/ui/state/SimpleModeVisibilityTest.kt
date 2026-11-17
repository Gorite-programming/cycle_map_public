package com.gorite.cyclemap.ui.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleModeVisibilityTest {
    @Test
    fun normalModeShowsEverythingExceptTheModeSwitch() {
        val v = SimpleModeVisibility.of(DisplayMode.NORMAL, isNavigationActive = false)
        assertTrue(v.topBar && v.homePanel && v.bottomSheetAndTabs && v.areaDownloadButton && v.poiPins)
        assertFalse(v.modeSwitchButton)
    }

    @Test
    fun simpleModeHidesSearchSheetsAndPinsButKeepsTheSwitch() {
        val v = SimpleModeVisibility.of(DisplayMode.SIMPLE, isNavigationActive = false)
        assertFalse(v.topBar || v.homePanel || v.bottomSheetAndTabs || v.areaDownloadButton || v.poiPins)
        assertTrue(v.modeSwitchButton)
    }

    @Test
    fun simpleModeHidesTheSwitchWhileNavigating() {
        assertFalse(SimpleModeVisibility.of(DisplayMode.SIMPLE, isNavigationActive = true).modeSwitchButton)
    }

    @Test
    fun cycleComputerLeavesTheMapChromeAlone() {
        // サイコンは地図の上に重ねる別画面で、下の通常画面の部品は変えない。
        val v = SimpleModeVisibility.of(DisplayMode.CYCLE_COMPUTER, isNavigationActive = false)
        assertTrue(v.topBar && v.homePanel && v.poiPins)
        assertFalse(v.modeSwitchButton)
    }
}
