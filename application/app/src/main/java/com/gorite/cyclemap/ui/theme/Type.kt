package com.gorite.cyclemap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)
/**
 * 「墨と朱」の文字。見出し・大きな数字 (display / headline / titleLarge) は明朝、本文とラベルはゴシックのまま。
 * 明朝は細く見えるので、見出しは標準より一段太くし、字間を少し空ける。
 */
private val base = androidx.compose.material3.Typography()

private fun TextStyle.mincho(weight: FontWeight = FontWeight.SemiBold) =
    copy(fontFamily = FontFamily.Serif, fontWeight = weight, letterSpacing = 0.02.em)

val SumiShuTypography = androidx.compose.material3.Typography(
    displayLarge = base.displayLarge.mincho(),
    displayMedium = base.displayMedium.mincho(),
    displaySmall = base.displaySmall.mincho(),
    headlineLarge = base.headlineLarge.mincho(),
    headlineMedium = base.headlineMedium.mincho(),
    headlineSmall = base.headlineSmall.mincho(),
    titleLarge = base.titleLarge.mincho(),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall,
    bodyLarge = Typography.bodyLarge,
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge,
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall,
)

/** 「墨と朱」の角の丸み。和紙の札のように、標準より少し角を立てる。 */
val SumiShuShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
)
