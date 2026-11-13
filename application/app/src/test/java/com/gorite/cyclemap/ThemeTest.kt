package com.gorite.cyclemap

import androidx.compose.ui.graphics.Color
import com.gorite.cyclemap.ui.theme.DarkColorScheme
import com.gorite.cyclemap.ui.theme.GoogleLightColorScheme
import com.gorite.cyclemap.ui.theme.LightCycleMapPalette
import com.gorite.cyclemap.ui.theme.SumiShu
import com.gorite.cyclemap.ui.theme.SumiShuColorScheme
import com.gorite.cyclemap.ui.theme.SumiShuPalette
import com.gorite.cyclemap.ui.theme.getAppColorScheme
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class ThemeTest {

    @Test
    fun darkColorScheme_isDefinedAndConsistent() {
        assertNotNull(DarkColorScheme)
        val bg = DarkColorScheme.background
        val surface = DarkColorScheme.surface
        assertNotNull(bg)
        assertNotNull(surface)
        // ダークは「墨と朱」: 墨の地、和紙色の文字、朱が primary
        assertEquals(Color(0xFF1C1A17), bg)
        assertEquals(Color(0xFF221F1C), surface)
        assertEquals(Color(0xFFEDE6DA), DarkColorScheme.onSurface)
        assertEquals(Color(0xFFE8603C), DarkColorScheme.primary)
    }

    @Test
    fun getAppColorScheme_alwaysReturnsDarkSchemeRegardlessOfContext() {
        // dynamicColor=false の場合は常に DarkColorScheme が返されること (デフォルト isDark = true)
        val scheme = getAppColorScheme(context = null, dynamicColor = false)
        assertEquals(DarkColorScheme, scheme)
    }

    @Test
    fun getAppColorScheme_returnsCorrectSchemeBasedOnIsDark() {
        assertEquals(SumiShuColorScheme, getAppColorScheme(isDark = true))
        assertEquals(GoogleLightColorScheme, getAppColorScheme(isDark = false))
    }

    @Test
    fun sumiShuTextIsReadableOnItsSurfaces() {
        // 本文 (和紙) と補足 (薄墨) は、墨のどの面の上でも WCAG AA (4.5:1) を満たす
        for (bg in listOf(SumiShu.Sumi0, SumiShu.Sumi1, SumiShu.Sumi2, SumiShu.Sumi3)) {
            assertTrue("washi on $bg", contrast(SumiShu.Washi, bg) >= 4.5)
            assertTrue("usuzumi on $bg", contrast(SumiShu.Usuzumi, bg) >= 4.5)
        }
        // 朱の上の文字 (墨) と、墨の上の朱 (アイコン・ルート) は 3:1 以上 (大きな文字・図形の基準)
        assertTrue(contrast(SumiShuPalette.onAccent, SumiShuPalette.accent) >= 4.5)
        assertTrue(contrast(SumiShu.Shu, SumiShu.Sumi2) >= 3.0)
        assertTrue(contrast(SumiShu.Kin, SumiShu.Sumi2) >= 4.5)
    }

    @Test
    fun palettesSeparateLightAndDark() {
        assertFalse(LightCycleMapPalette.isDark)
        assertTrue(SumiShuPalette.isDark)
        // ライトは以前の Google マップ風の値のまま
        assertEquals(Color(0xFF1A73E8), LightCycleMapPalette.accent)
        assertEquals(Color.White, LightCycleMapPalette.mapControlSurface)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
