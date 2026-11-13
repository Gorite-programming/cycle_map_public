package com.gorite.cyclemap.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * 「墨と朱」(ダークモード) の色の素。画面の部品はここを直接使わず、[CycleMapPalette] の役割名か
 * MaterialTheme.colorScheme を通して使う。
 *
 * 地図 (MapLibre のスタイル) の夜の色も同じ系統にそろえている (VectorMapThemeHelper)。
 */
object SumiShu {
    /** 墨: 背景から浮き上がる順に明るくなる。 */
    val Sumi0 = Color(0xFF1C1A17) // 画面・地図の地
    val Sumi1 = Color(0xFF221F1C) // シート
    val Sumi2 = Color(0xFF2A2622) // カード・案内の帯・地図の上のボタン
    val Sumi3 = Color(0xFF34302A) // チップ・押せる面
    val Sumi4 = Color(0xFF3F3A33) // 区切り線
    val Sumi5 = Color(0xFF5A5248) // 枠線

    /** 和紙: 文字。 */
    val Washi = Color(0xFFEDE6DA) // 本文
    val Usuzumi = Color(0xFFA89F92) // 補足
    val Nibi = Color(0xFF7D7468) // さらに控えめ (無効・目盛り)

    /** 朱: 操作・ルート・現在の案内。 */
    val Shu = Color(0xFFE8603C)
    val ShuDeep = Color(0xFF4A2418) // 朱の面 (選択中の背景)
    val ShuPale = Color(0xFFFFD9CC) // 朱の面の上の文字

    /** 差し色。朱と取り合わせる伝統色。 */
    val Kin = Color(0xFFD8A84E) // 金茶: お気に入り・注意
    val KinDeep = Color(0xFF3A2F1E)
    val Koke = Color(0xFF8DB580) // 苔: 成功・到着
    val KokeDeep = Color(0xFF26301F)
    val Ai = Color(0xFF7FA7C9) // 藍: 情報・水
    val Beni = Color(0xFFF07167) // 紅: 危険・記録中・エラー (朱より赤く明るい)
    val BeniDeep = Color(0xFF4A1E1A)
}

/**
 * Material の ColorScheme では表せない、CycleMap 固有の色の役割。
 * 昼 (ライト) は以前の Google マップ風の値のまま、夜 (ダーク) は「墨と朱」。
 *
 * 画面では [CycleMapTheme.palette] で取り出す。Canvas の描画の中 (Composable でない所) では、
 * 外側で取り出した値を使うこと。
 */
@Immutable
data class CycleMapPalette(
    val isDark: Boolean,

    /** 地図の上に浮くボタン・コンパスなど。 */
    val mapControlSurface: Color,
    val mapControlIcon: Color,
    val mapControlIconMuted: Color,
    val mapControlDivider: Color,
    /** コンパスの北と南の針。 */
    val compassNorth: Color,
    val compassSouth: Color,

    /** 操作の強調 (追従中・傾き・選択中)。ライトは Google の青、ダークは朱。 */
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,

    /** 案内中の上部の帯。 */
    val navBanner: Color,
    val navBannerRerouting: Color,
    val onNavBanner: Color,
    val onNavBannerSub: Color,
    /** 曲がる向きの矢印の下地 (ダークのみ朱の丸)。透明なら下地なし。 */
    val navTurnBadge: Color,
    val laneActive: Color,
    val laneActiveIcon: Color,
    val laneInactive: Color,

    /** 「ナビを開始」など、画面でいちばん大事な押すところ。 */
    val primaryAction: Color,
    val onPrimaryAction: Color,

    /** 危険・記録中・終了。 */
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,

    /** 成功・到着。 */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,

    /** 注意 (データの不足・オフライン従量)。 */
    val warning: Color,
    val warningContainer: Color,

    /** 到着予定時刻など、案内中に目立たせる数字。 */
    val etaText: Color,

    /** お気に入りの星。 */
    val favorite: Color,

    /** 見出しや大きな数字の書体 (ダークは明朝)。 */
    val displayFontFamily: FontFamily,
)

internal val LightCycleMapPalette = CycleMapPalette(
    isDark = false,
    mapControlSurface = Color.White,
    mapControlIcon = Color(0xFF3C4043),
    mapControlIconMuted = Color(0xFF5F6368),
    mapControlDivider = Color(0xFFE8EAED),
    compassNorth = Color(0xFFD93025),
    compassSouth = Color(0xFFBDC1C6),
    accent = Color(0xFF1A73E8),
    onAccent = Color.White,
    accentContainer = Color(0xFFE8F0FE),
    onAccentContainer = Color(0xFF1A73E8),
    navBanner = Color(0xFF0F9D58),
    navBannerRerouting = Color(0xFF1E8E3E),
    onNavBanner = Color.White,
    onNavBannerSub = Color(0xFFD4EED8),
    navTurnBadge = Color.Transparent,
    laneActive = Color.White,
    laneActiveIcon = Color(0xFF0F9D58),
    laneInactive = Color(0x55FFFFFF),
    primaryAction = Color(0xFF006A60),
    onPrimaryAction = Color.White,
    danger = Color(0xFFD93025),
    onDanger = Color.White,
    dangerContainer = Color(0xFFFCE8E6),
    success = Color(0xFF137333),
    onSuccess = Color.White,
    successContainer = Color(0xFFE6F4EA),
    warning = Color(0xFFE37400),
    warningContainer = Color(0xFFFEF7E0),
    etaText = Color(0xFF0F9D58),
    favorite = Color(0xFFFFB300),
    displayFontFamily = FontFamily.Default,
)

internal val SumiShuPalette = CycleMapPalette(
    isDark = true,
    mapControlSurface = SumiShu.Sumi2,
    mapControlIcon = SumiShu.Washi,
    mapControlIconMuted = SumiShu.Usuzumi,
    mapControlDivider = SumiShu.Sumi4,
    compassNorth = SumiShu.Shu,
    compassSouth = SumiShu.Nibi,
    accent = SumiShu.Shu,
    onAccent = SumiShu.Sumi0,
    accentContainer = SumiShu.ShuDeep,
    onAccentContainer = SumiShu.ShuPale,
    navBanner = SumiShu.Sumi2,
    navBannerRerouting = SumiShu.KinDeep,
    onNavBanner = SumiShu.Washi,
    onNavBannerSub = SumiShu.Usuzumi,
    navTurnBadge = SumiShu.Shu,
    laneActive = SumiShu.Shu,
    laneActiveIcon = SumiShu.Sumi0,
    laneInactive = SumiShu.Sumi4,
    primaryAction = SumiShu.Shu,
    onPrimaryAction = SumiShu.Sumi0,
    danger = SumiShu.Beni,
    onDanger = SumiShu.Sumi0,
    dangerContainer = SumiShu.BeniDeep,
    success = SumiShu.Koke,
    onSuccess = SumiShu.Sumi0,
    successContainer = SumiShu.KokeDeep,
    warning = SumiShu.Kin,
    warningContainer = SumiShu.KinDeep,
    etaText = SumiShu.Kin,
    favorite = SumiShu.Kin,
    displayFontFamily = FontFamily.Serif,
)

internal val LocalCycleMapPalette = staticCompositionLocalOf { LightCycleMapPalette }

/** `CycleMapTheme.palette` で CycleMap 固有の色を取り出す (MaterialTheme.colorScheme と同じ使い方)。 */
object CycleMapTheme {
    val palette: CycleMapPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalCycleMapPalette.current
}
