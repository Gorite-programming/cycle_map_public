package com.gorite.cyclemap.ui.layout

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandscapeControlsLayoutTest {
    @Test
    fun placesControlsBelowMeasuredTopBarAndWarningBanner() {
        val effectiveTopPadding = 24.dp
        val topOverlayHeight = 156.dp // SearchTopBar + GPS警告バナー + カテゴリチップ
        val topPadding = resolveLandscapeControlsTopPadding(
            effectiveTopPadding = effectiveTopPadding,
            topOverlayHeight = topOverlayHeight,
            isNavigationActive = false,
            showTopBar = true,
        )

        val topOverlayBottom = effectiveTopPadding + 8.dp + topOverlayHeight
        assertTrue(topPadding > topOverlayBottom)
        assertEquals(effectiveTopPadding + 8.dp + topOverlayHeight + 8.dp, topPadding)
    }

    @Test
    fun usesSafeFallbackBeforeFirstTopBarMeasurement() {
        val topPadding = resolveLandscapeControlsTopPadding(
            effectiveTopPadding = 16.dp,
            topOverlayHeight = 0.dp,
            isNavigationActive = false,
            showTopBar = true,
        )

        assertEquals(16.dp + 132.dp, topPadding)
    }

    @Test
    fun keepsCompactTopPaddingInSimpleOrPreviewModeWithoutBanners() {
        val topPadding = resolveLandscapeControlsTopPadding(
            effectiveTopPadding = 16.dp,
            topOverlayHeight = 0.dp,
            isNavigationActive = false,
            showTopBar = false,
        )

        assertEquals(16.dp + 12.dp, topPadding)
    }

    @Test
    fun movesBelowWarningBannerInSimpleOrPreviewMode() {
        val effectiveTopPadding = 16.dp
        val bannerHeight = 44.dp
        val topPadding = resolveLandscapeControlsTopPadding(
            effectiveTopPadding = effectiveTopPadding,
            topOverlayHeight = bannerHeight,
            isNavigationActive = false,
            showTopBar = false,
        )

        assertEquals(effectiveTopPadding + 8.dp + bannerHeight + 8.dp, topPadding)
    }

    @Test
    fun placesControlsBelowLandscapeNavHudAndBannersDuringNavigation() {
        val effectiveTopPadding = 20.dp
        val navOverlayHeight = 96.dp // LandscapeNavHudBar + GPSバナー
        val topPadding = resolveLandscapeControlsTopPadding(
            effectiveTopPadding = effectiveTopPadding,
            topOverlayHeight = navOverlayHeight,
            isNavigationActive = true,
            showTopBar = false,
        )

        assertEquals(effectiveTopPadding + 8.dp + navOverlayHeight + 8.dp, topPadding)

        val unmeasuredPadding = resolveLandscapeControlsTopPadding(
            effectiveTopPadding = effectiveTopPadding,
            topOverlayHeight = 0.dp,
            isNavigationActive = true,
            showTopBar = false,
        )
        assertEquals(effectiveTopPadding + 104.dp, unmeasuredPadding)
    }
}
