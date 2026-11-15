package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.ui.theme.SumiShu
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gorite.cyclemap.R
import com.gorite.cyclemap.contract.LaneInfo
import com.gorite.cyclemap.contract.TurnType
import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.ui.cycling.ElevationSample
import com.gorite.cyclemap.ui.cycling.GradeAhead
import com.gorite.cyclemap.ui.cycling.GradeKind
import com.gorite.cyclemap.ui.cycling.elevationAt
import com.gorite.cyclemap.ui.cycling.gradeAhead
import com.gorite.cyclemap.ui.cycling.gradeAt
import com.gorite.cyclemap.ui.cycling.remainingElevationGain
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 案内画面デザイン案C「サイクコン」: 上端のコンパクトな案内バー + 下端の数値パネル
 * (速度・残り・勾配・先の標高・到着・電池)。
 */

@Immutable
internal data class NavPalette(
    val card: Color,
    val chip: Color,
    val go: Color,
    val goFg: Color,
    val warn: Color,
    val text: Color,
    val sub: Color,
    val stop: Color,
    val route: Color,
    /** リルート中の帯と、その上の文字。 */
    val reroute: Color,
    val onReroute: Color,
    /** 数値の書体。null なら細身のゴシック (sans-serif-condensed)。 */
    val numFont: FontFamily? = null,
)

/** 夜は「墨と朱」: 墨の札に朱の矢印、数字は明朝、速度は金茶。 */
private val NAV_DARK = NavPalette(
    card = SumiShu.Sumi2, chip = SumiShu.Sumi3, go = SumiShu.Shu, goFg = SumiShu.Sumi0,
    warn = SumiShu.Kin, text = SumiShu.Washi, sub = SumiShu.Usuzumi, stop = SumiShu.Sumi3,
    route = SumiShu.Shu,
    reroute = SumiShu.KinDeep, onReroute = SumiShu.Kin,
    numFont = FontFamily.Serif,
)
private val NAV_LIGHT = NavPalette(
    card = Color(0xFFFFFFFF), chip = Color(0xFFEDF1F3), go = Color(0xFF00755F), goFg = Color(0xFFFFFFFF),
    warn = Color(0xFFA85F00), text = Color(0xFF11171C), sub = Color(0xFF55616B), stop = Color(0xFFE6EBEE),
    route = Color(0xFF1A73E8),
    reroute = Color(0xFFB3261E), onReroute = Color.White,
)

@Composable
internal fun rememberNavPalette(): NavPalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (dark) NAV_DARK else NAV_LIGHT
}

/** 数値用の細身の太字 (端末の Roboto Condensed)。桁がそろうよう tnum を有効にする。 */
// 遅延初期化: クラス読み込み時に android.graphics を触らない (JVM ユニットテストで純関数を使えるようにする)
private val NumFont: FontFamily by lazy { FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD)) }

private fun numStyle(sizeSp: Int, color: Color, palette: NavPalette) = TextStyle(
    fontFamily = palette.numFont ?: NumFont,
    fontWeight = FontWeight.Bold,
    fontSize = sizeSp.sp,
    lineHeight = sizeSp.sp,
    color = color,
    fontFeatureSettings = "tnum",
)

/** 次のあとの案内の予告 ("そのあと 右折 90 m")。 */
internal data class NavNextHint(val turnType: TurnType, val distanceMeters: Double)

/** 距離を「数字 + 単位」に分ける。読み取りやすさのため近いほど細かく、遠いほど粗く丸める。 */
internal fun splitDistance(meters: Double): Pair<String, String> {
    val m = meters.coerceAtLeast(0.0)
    // 50m刻みに丸めた結果が 1000 になる (975m以上) ときは km 表示にそろえる
    val roundedMeters = ((m / 50.0).roundToInt() * 50)
    return when {
        m < 100.0 -> (((m / 10.0).roundToInt() * 10).coerceAtLeast(10)).toString() to "m"
        roundedMeters < 1000 -> roundedMeters.toString() to "m"
        else -> String.format(java.util.Locale.US, "%.1f", m / 1000.0) to "km"
    }
}

/** 坂の予告文。null (この先は平坦) も含めて、現在地から見た言い方にする。 */
internal fun gradeAheadText(ahead: GradeAhead?): String {
    if (ahead == null) return "この先は平坦"
    val kind = if (ahead.kind == GradeKind.CLIMB) "上り" else "下り"
    val grade = ahead.gradePct.roundToInt().coerceAtLeast(1)
    return if (ahead.startsInM < 20.0) {
        val rest = ahead.lengthM?.let { " あと${splitDistance(it).let { (n, u) -> n + u }}" } ?: " が続きます"
        "$kind$grade%$rest"
    } else {
        val (n, u) = splitDistance(ahead.startsInM)
        "$n$u" + "先から $kind$grade%"
    }
}

/** 上端のコンパクトな案内バー。 */
@Composable
internal fun NavTopBarC(
    turnType: TurnType,
    distanceMeters: Double,
    instructionText: String,
    roadLabel: String?,
    next: NavNextHint?,
    isRerouting: Boolean,
    modifier: Modifier = Modifier,
    laneHints: List<LaneInfo> = emptyList(),
) {
    val p = rememberNavPalette()
    val container = if (isRerouting) p.reroute else p.card
    val (num, unit) = splitDistance(distanceMeters)
    val a11y = if (isRerouting) "ルートを再計算中" else "${num}${unit}先、$instructionText"
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11y },
        color = container,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isRerouting) 52.dp else 64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isRerouting) p.onReroute.copy(alpha = 0.2f) else p.go),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (isRerouting) R.drawable.ic_lucide_route else turnTypeToDrawableRes(turnType)),
                        contentDescription = null,
                        tint = if (isRerouting) p.onReroute else p.goFg,
                        modifier = Modifier.size(if (isRerouting) 36.dp else 44.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (isRerouting) {
                    Text(
                        text = "ルートを再計算中",
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.onReroute),
                    )
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(text = num, style = numStyle(46, p.text, p))
                            Spacer(Modifier.width(3.dp))
                            Text(text = unit, style = numStyle(20, p.sub, p), modifier = Modifier.padding(bottom = 3.dp))
                        }
                        Text(
                            text = instructionText,
                            style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = p.text),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!roadLabel.isNullOrEmpty()) {
                            Text(
                                text = roadLabel,
                                style = TextStyle(fontSize = 14.sp, color = p.sub),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (shouldShowLaneGuidance(laneHints, isRerouting)) {
                            Spacer(Modifier.height(6.dp))
                            LaneGuidanceChips(laneHints)
                        }
                    }
                }
            }
            if (next != null && !isRerouting) {
                HorizontalDivider(thickness = 1.dp, color = p.sub.copy(alpha = 0.2f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (p.card.luminance() < 0.5f) p.goFg else p.chip)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "そのあと", style = TextStyle(fontSize = 14.sp, color = p.sub))
                    Icon(
                        painter = painterResource(turnTypeToDrawableRes(next.turnType)),
                        contentDescription = null,
                        tint = p.text,
                        modifier = Modifier.size(20.dp),
                    )
                    val (nn, nu) = splitDistance(next.distanceMeters)
                    Text(text = nn + nu, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text))
                }
            }
        }
    }
}

internal fun shouldShowLaneGuidance(laneHints: List<LaneInfo>, isRerouting: Boolean): Boolean =
    laneHints.isNotEmpty() && !isRerouting

@Composable
internal fun LaneGuidanceChips(laneHints: List<LaneInfo>) {
    val palette = CycleMapTheme.palette
    // The light lane palette uses translucent white over the navigation banner.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.navBanner)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        laneHints.forEach { lane ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (lane.isRecommended) palette.laneActive else palette.laneInactive,
                modifier = Modifier.size(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(turnTypeToDrawableRes(lane.direction)),
                        contentDescription = stringResource(R.string.lane_guidance),
                        tint = if (lane.isRecommended) palette.laneActiveIcon else palette.onNavBanner,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 下端の数値パネルが使う入力。画面側の計算結果をまとめたもの。 */
@Immutable
internal data class NavPanelData(
    val speedKmh: Double,
    val remainingMeters: Double,
    val gradePct: Double?,
    val ahead: GradeAhead?,
    val remainingGainM: Double?,
    val etaEpochMillis: Long?,
    val remainingSeconds: Double?,
    val profile: List<ElevationSample>,
    val progressM: Double,
    val windowM: Double,
)

/** ルート進捗と標高から、パネルの表示値をまとめる。 */
internal fun buildNavPanelData(
    speedKmh: Double,
    remainingMeters: Double,
    profile: List<ElevationSample>,
    progressM: Double,
    etaEpochMillis: Long?,
    remainingSeconds: Double?,
): NavPanelData = NavPanelData(
    speedKmh = speedKmh,
    remainingMeters = remainingMeters,
    gradePct = gradeAt(profile, progressM),
    ahead = gradeAhead(profile, progressM),
    remainingGainM = remainingElevationGain(profile, progressM),
    etaEpochMillis = etaEpochMillis,
    remainingSeconds = remainingSeconds?.takeIf { it.isFinite() },
    profile = profile,
    progressM = progressM,
    windowM = minOf(300.0, remainingMeters).coerceAtLeast(60.0),
)

@Composable
internal fun NavBottomPanelC(
    data: NavPanelData,
    batteryPercent: Int?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val p = rememberNavPalette()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = p.card,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricCard(
                    label = "速度", modifier = Modifier.weight(1.3f).fillMaxHeight(), palette = p,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = data.speedKmh.coerceAtLeast(0.0).roundToInt().toString(),
                            style = numStyle(56, p.warn, p),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(text = "km/h", style = numStyle(18, p.sub, p), modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                val (rn, ru) = splitRemaining(data.remainingMeters)
                MetricCard(label = "残り", modifier = Modifier.weight(1f).fillMaxHeight(), palette = p) {
                    ValueWithUnit(rn, ru, p)
                }
                MetricCard(label = "勾配", modifier = Modifier.weight(1f).fillMaxHeight(), palette = p) {
                    val g = data.gradePct
                    ValueWithUnit(if (g == null) "--" else String.format("%+.0f", g), "%", p)
                }
            }

            ElevationAheadCard(data = data, palette = p)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = data.remainingSeconds?.let { formatDuration(it) } ?: "--",
                            style = numStyle(20, p.warn, p),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = data.etaEpochMillis?.let { "${formatClock(it)}着" } ?: "到着時刻計算中",
                            style = TextStyle(fontSize = 11.sp, color = p.sub),
                            modifier = Modifier.padding(bottom = 2.dp),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    if (batteryPercent != null) BatteryBadge(percent = batteryPercent, color = p.sub)
                }
                IconButton(onClick = onToggleMute, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(if (isMuted) R.drawable.ic_lucide_volume_x else R.drawable.ic_lucide_volume_2),
                        contentDescription = if (isMuted) "音声案内をオンにする" else "音声案内をミュートする",
                        tint = p.sub,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Surface(
                    onClick = onStopNavigation,
                    modifier = Modifier
                        .height(44.dp)
                        .widthIn(min = 84.dp)
                        .semantics { contentDescription = "ナビを終了" },
                    shape = RoundedCornerShape(22.dp),
                    color = p.stop,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    ) {
                        Text(text = "終了", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text))
                        Icon(
                            painter = painterResource(R.drawable.ic_lucide_x),
                            contentDescription = null,
                            tint = p.text,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun splitRemaining(meters: Double): Pair<String, String> {
    val m = meters.coerceAtLeast(0.0)
    return if (m < 1000.0) m.roundToInt().toString() to "m" else String.format("%.1f", m / 1000.0) to "km"
}

private fun formatClock(epochMillis: Long): String {
    val t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return String.format("%02d:%02d", t.hour, t.minute)
}

@Composable
private fun MetricCard(
    label: String,
    palette: NavPalette,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.chip)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = TextStyle(fontSize = 11.sp, letterSpacing = 0.8.sp, color = palette.sub))
        content()
    }
}

@Composable
private fun ValueWithUnit(value: String, unit: String, palette: NavPalette) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = numStyle(36, palette.text, palette))
        Spacer(Modifier.width(2.dp))
        Text(text = unit, style = numStyle(15, palette.sub, palette), modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun ElevationAheadCard(data: NavPanelData, palette: NavPalette) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.chip)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = gradeAheadText(data.ahead),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.text),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            data.remainingGainM?.let {
                Text(
                    text = "残り 獲得標高 +${it.roundToInt()} m",
                    style = TextStyle(fontSize = 12.sp, color = palette.sub),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ElevationStrip(data = data, palette = palette, modifier = Modifier.fillMaxWidth().height(48.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val w = data.windowM
            val axis = TextStyle(fontSize = 11.sp, color = palette.sub)
            Text("いま", style = axis)
            Text(splitDistance(w / 2.0).let { (n, u) -> n + u }, style = axis, maxLines = 1)
            Text(
                splitDistance(w).let { (n, u) -> n + u },
                style = axis,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** 現在地から前方 [NavPanelData.windowM] の標高の断面。坂の区間を帯で強調する。 */
@Composable
private fun ElevationStrip(data: NavPanelData, palette: NavPalette, modifier: Modifier = Modifier) {
    val samples = remember(data.profile, data.progressM, data.windowM) {
        val n = 24
        List(n + 1) { i ->
            val d = data.progressM + data.windowM * i / n
            elevationAt(data.profile, d)
        }
    }
    Canvas(modifier = modifier) {
        val valid = samples.filterNotNull()
        if (valid.size < 2) return@Canvas
        val lo = valid.min()
        val hi = valid.max()
        // 標高差が小さいときに平坦な線が大きな波に見えないよう、最小でも 10 m の幅で描く
        val span = maxOf(hi - lo, 10.0)
        val mid = (hi + lo) / 2.0
        val bottom = mid - span / 2.0
        val w = size.width
        val h = size.height
        fun x(i: Int) = w * i / (samples.size - 1)
        fun y(e: Double) = (h - 4f - ((e - bottom) / span).toFloat() * (h - 10f))

        data.ahead?.let { a ->
            val x0 = (a.startsInM / data.windowM).toFloat().coerceIn(0f, 1f) * w
            val x1 = (a.lengthM?.let { (a.startsInM + it) / data.windowM } ?: 1.0).toFloat().coerceIn(0f, 1f) * w
            if (x1 > x0) drawRect(palette.warn.copy(alpha = 0.16f), topLeft = Offset(x0, 0f), size = Size(x1 - x0, h))
        }

        val line = Path()
        val fill = Path()
        var started = false
        samples.forEachIndexed { i, e ->
            if (e == null) return@forEachIndexed
            val px = x(i)
            val py = y(e)
            if (!started) {
                line.moveTo(px, py); fill.moveTo(px, h); fill.lineTo(px, py); started = true
            } else {
                line.lineTo(px, py); fill.lineTo(px, py)
            }
        }
        fill.lineTo(w, h); fill.close()
        drawPath(fill, palette.route.copy(alpha = 0.22f))
        drawPath(line, palette.route, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(palette.text, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), h), strokeWidth = 2.dp.toPx())
        samples.firstOrNull()?.let { drawCircle(palette.text, radius = 4.dp.toPx(), center = Offset(1.dp.toPx(), y(it))) }
    }
}

@Composable
private fun BatteryBadge(percent: Int, color: Color) {
    Row(
        modifier = Modifier.semantics { contentDescription = "バッテリー残量 ${percent}%" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.size(width = 22.dp, height = 11.dp)) {
            val stroke = 1.6.dp.toPx()
            val body = Size(size.width - 3.dp.toPx(), size.height)
            drawRoundRect(color, size = body, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(stroke))
            val inner = (body.width - 4.dp.toPx()) * (percent.coerceIn(0, 100) / 100f)
            drawRoundRect(color, topLeft = Offset(2.dp.toPx(), 2.dp.toPx()), size = Size(inner, body.height - 4.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
            drawRect(color, topLeft = Offset(body.width + 0.5.dp.toPx(), size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f))
        }
        Text(text = "$percent%", style = TextStyle(fontSize = 12.sp, color = color))
    }
}

/** バッテリー残量 (%) を購読する。取得できなければ null。 */
@Composable
internal fun rememberBatteryPercent(): Int? {
    val context = LocalContext.current
    var percent by remember { mutableStateOf(readBatteryPercent(context)) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                percent = readBatteryPercent(context)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return percent
}

private fun readBatteryPercent(context: Context): Int? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level >= 0 && scale > 0) level * 100 / scale else null
}
