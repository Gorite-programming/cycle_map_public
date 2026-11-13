package com.gorite.cyclemap.ui.theme

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Google Maps スタイルのクリーンなライト配色。
 * 白背景 (#FFFFFF)、Google Blue (#1A73E8)、Google Dark Gray (#202124)、Medium Gray (#5F6368)。
 */
@VisibleForTesting
internal val GoogleLightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE),
    onPrimaryContainer = Color(0xFF1A73E8),
    secondary = Color(0xFF1E8E3E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F4EA),
    onSecondaryContainer = Color(0xFF137333),
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    error = Color(0xFFD93025),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFFC5221F),
    outline = Color(0xFFDADCE0),
    outlineVariant = Color(0xFFE8EAED),
)

/**
 * 「墨と朱」のダーク配色。温かみのある墨色の地に、朱を操作とルートの色として使う。
 * 色の素は [SumiShu]、Material で表せない役割は [SumiShuPalette]。
 */
@VisibleForTesting
internal val SumiShuColorScheme = darkColorScheme(
    primary = SumiShu.Shu,
    onPrimary = SumiShu.Sumi0,
    primaryContainer = SumiShu.ShuDeep,
    onPrimaryContainer = SumiShu.ShuPale,
    inversePrimary = Color(0xFFA83A1E),
    secondary = Color(0xFFC9B79C),
    onSecondary = SumiShu.Sumi0,
    secondaryContainer = SumiShu.Sumi3,
    onSecondaryContainer = SumiShu.Washi,
    tertiary = SumiShu.Kin,
    onTertiary = SumiShu.Sumi0,
    tertiaryContainer = SumiShu.KinDeep,
    onTertiaryContainer = Color(0xFFF3DDB0),
    background = SumiShu.Sumi0,
    onBackground = SumiShu.Washi,
    surface = SumiShu.Sumi1,
    onSurface = SumiShu.Washi,
    surfaceVariant = SumiShu.Sumi3,
    onSurfaceVariant = SumiShu.Usuzumi,
    surfaceTint = SumiShu.Shu,
    inverseSurface = SumiShu.Washi,
    inverseOnSurface = SumiShu.Sumi0,
    error = SumiShu.Beni,
    onError = SumiShu.Sumi0,
    errorContainer = SumiShu.BeniDeep,
    onErrorContainer = Color(0xFFFFDAD6),
    outline = SumiShu.Sumi5,
    outlineVariant = SumiShu.Sumi4,
    scrim = Color(0xFF000000),
    surfaceBright = SumiShu.Sumi3,
    surfaceDim = SumiShu.Sumi0,
    surfaceContainerLowest = Color(0xFF171512),
    surfaceContainerLow = SumiShu.Sumi1,
    surfaceContainer = SumiShu.Sumi2,
    surfaceContainerHigh = SumiShu.Sumi3,
    surfaceContainerHighest = SumiShu.Sumi4,
)

@VisibleForTesting
internal val DarkColorScheme = SumiShuColorScheme

@VisibleForTesting
internal fun getAppColorScheme(
    context: Context? = null,
    dynamicColor: Boolean = false,
    isDark: Boolean = true,
): ColorScheme {
    return if (isDark) {
        SumiShuColorScheme
    } else {
        GoogleLightColorScheme
    }
}

/**
 * CycleMap 専用テーマ。昼は Google マップ風、夜は「墨と朱」。
 * 色に加えて、CycleMap 固有の色の役割 ([CycleMapTheme.palette])・文字・角の丸みも昼夜で切り替える。
 */
@Composable
fun CycleMapTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = getAppColorScheme(
        context = context,
        dynamicColor = dynamicColor,
        isDark = darkTheme,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCycleMapPalette provides if (darkTheme) SumiShuPalette else LightCycleMapPalette,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (darkTheme) SumiShuTypography else Typography,
            shapes = if (darkTheme) SumiShuShapes else androidx.compose.material3.Shapes(),
            content = content,
        )
    }
}

/**
 * 緯度経度から日没後（夜間）かどうかを判定するヘルパー。
 */
fun isSolarNight(latitude: Double, longitude: Double): Boolean {
    return SolarCalculator.isNight(latitude, longitude)
}