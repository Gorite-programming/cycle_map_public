package com.gorite.cyclemap.ui.cycling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.tracking.RideFormat
import com.gorite.cyclemap.tracking.RideSnapshot
import com.gorite.cyclemap.tracking.RideState
import com.gorite.cyclemap.ui.theme.CycleMapTheme
import kotlinx.coroutines.delay

internal fun gpxStatusBadgeLabel(isGpxRecording: Boolean, paused: Boolean = false): String = when {
    !isGpxRecording -> "GPX停止中"
    // 一時停止中は点を記録しない (記録は続いているので「終了して保存」は出したまま)
    paused -> "GPX一時停止中"
    else -> "GPX記録中"
}

internal fun shouldShowFinishAndSave(isGpxRecording: Boolean): Boolean = isGpxRecording

internal fun finishAndSaveButtonLabel(fontScale: Float): String =
    if (fontScale > 1.3f) "保存して終了" else "記録を終了して保存"

/** サイコン画面上端の時計と走行状態・GPX記録状態バッジ。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CycleComputerStatusBar(
    clockText: String,
    state: RideState,
    modifier: Modifier = Modifier,
    isGpxRecording: Boolean = false,
) {
    val palette = CycleMapTheme.palette
    val (stateLabel, dotColor, containerColor, textColor) = when (state) {
        RideState.RUNNING -> QuadColorLabel(
            label = "走行中",
            dot = palette.success,
            container = palette.successContainer,
            text = palette.success,
        )
        RideState.PAUSED -> QuadColorLabel(
            label = "一時停止",
            dot = palette.warning,
            container = palette.warningContainer,
            text = palette.warning,
        )
        RideState.IDLE -> QuadColorLabel(
            label = "待機",
            dot = MaterialTheme.colorScheme.onSurfaceVariant,
            container = MaterialTheme.colorScheme.surfaceContainer,
            text = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val gpxLabel = gpxStatusBadgeLabel(isGpxRecording, paused = state == RideState.PAUSED)
    val gpxContainer = if (isGpxRecording) palette.dangerContainer else MaterialTheme.colorScheme.surface
    val gpxContent = if (isGpxRecording) palette.danger else MaterialTheme.colorScheme.onSurfaceVariant
    val gpxBorderColor = if (isGpxRecording) palette.danger else MaterialTheme.colorScheme.outlineVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = clockText,
            style = TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            // GPX 記録状態バッジ (角丸矩形＋枠線で、走行状態の丸ピルと一目で区別する)
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = gpxContainer,
                border = BorderStroke(1.dp, gpxBorderColor),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(gpxContent),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = gpxLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = gpxContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // サイコンの走行状態バッジ (自動一時停止/一時停止など)
            Surface(
                shape = CircleShape,
                color = containerColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stateLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private data class QuadColorLabel(
    val label: String,
    val dot: Color,
    val container: Color,
    val text: Color,
)

/** 画面主役の現在速度表示。一時停止中は数字を暗くして状態を一目で区別する。 */
@Composable
internal fun SpeedHeroDisplay(
    speedKmh: Double,
    isPaused: Boolean,
    speedFontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val palette = CycleMapTheme.palette
    val numberColor = if (isPaused) {
        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.onBackground
    }
    val unitColor = if (isPaused) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = RideFormat.speed(speedKmh),
            style = TextStyle(
                fontFamily = palette.displayFontFamily,
                fontSize = speedFontSize,
                lineHeight = speedFontSize * 0.92f,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
                color = numberColor,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "km/h",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = unitColor,
        )
    }
}

/**
 * 目的地の帯。目的地が null のときも高さを詰めずに空欄を保ち、上下の数字の位置を動かさない。
 */
@Composable
internal fun DestinationBand(
    destination: CycleComputerDestination?,
    bandHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (destination == null) {
        Spacer(
            modifier = modifier
                .fillMaxWidth()
                .height(bandHeight),
        )
        return
    }

    val palette = CycleMapTheme.palette
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(bandHeight),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (destination.isStraightLine) {
                destination.relativeBearingDegrees?.let { bearing ->
                    BearingArrowBadge(
                        bearingDegrees = bearing,
                        badgeColor = palette.accentContainer,
                        arrowColor = palette.accent,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "目的地まで",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "直線 ${RideFormat.distance(destination.remainingMeters)}",
                        style = TextStyle(
                            fontFamily = palette.displayFontFamily,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "残り ${RideFormat.distance(destination.remainingMeters)}",
                        style = TextStyle(
                            fontFamily = palette.displayFontFamily,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = palette.accent,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val turnLine = buildTurnLine(destination.nextTurnDistanceM, destination.nextTurnText)
                    if (turnLine.isNotEmpty()) {
                        Text(
                            text = turnLine,
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun buildTurnLine(distanceM: Double?, turnText: String?): String {
    val distStr = distanceM?.let { RideFormat.distance(it) }
    val textStr = turnText?.takeIf { it.isNotBlank() }
    return listOfNotNull(distStr, textStr).joinToString("  ")
}

/** 進行方向に対する相対方位 (0=真正面、90=右、-90=左) を描く矢印バッジ。 */
@Composable
private fun BearingArrowBadge(
    bearingDegrees: Double,
    badgeColor: Color,
    arrowColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(badgeColor),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            rotate(degrees = bearingDegrees.toFloat()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = size.minDimension * 0.34f
                val path = Path().apply {
                    moveTo(cx, cy - r)
                    lineTo(cx + r * 0.72f, cy + r * 0.45f)
                    lineTo(cx + r * 0.22f, cy + r * 0.22f)
                    lineTo(cx + r * 0.22f, cy + r * 0.92f)
                    lineTo(cx - r * 0.22f, cy + r * 0.92f)
                    lineTo(cx - r * 0.22f, cy + r * 0.22f)
                    lineTo(cx - r * 0.72f, cy + r * 0.45f)
                    close()
                }
                drawPath(path = path, color = arrowColor)
            }
        }
    }
}

/** 2 列表示の走行項目タイル。主な数字は 36sp 以上・等幅数字でちらつきを防ぐ。 */
@Composable
internal fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    compact: Boolean = false,
    valueSizeSp: Int = 36,
) {
    val palette = CycleMapTheme.palette
    val valueSize = valueSizeSp.sp
    val verticalPadding = if (compact) 6.dp else 8.dp

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = verticalPadding),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = value,
                    style = TextStyle(
                        fontFamily = palette.displayFontFamily,
                        fontSize = valueSize,
                        lineHeight = valueSize,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
        }
    }
}

/** 走行距離・走行時間・平均速度・最高速度・獲得標高の 2 列グリッド。 */
@Composable
internal fun StatsGrid(
    snapshot: RideSnapshot,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val gap = if (compact) 6.dp else 8.dp
    if (compact) {
        // 横画面 (高さ約 360dp): 2 列 x 3 行だと、開始・リセット・通常へ戻るの操作ボタンが下で切れる (S26 実機)。
        // 3 列 x 2 行に詰めて、ボタンの高さを確保する
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                MetricTile("走行距離", RideFormat.distance(snapshot.distanceM), Modifier.weight(1f), compact = true, valueSizeSp = 28)
                MetricTile("走行時間", RideFormat.duration(snapshot.movingTimeS), Modifier.weight(1f), compact = true, valueSizeSp = 28)
                MetricTile("獲得標高", RideFormat.elevationGain(snapshot.elevationGainM), Modifier.weight(1f), compact = true, valueSizeSp = 28)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                MetricTile("平均速度", RideFormat.speed(snapshot.averageKmh), Modifier.weight(1f), unit = "km/h", compact = true, valueSizeSp = 28)
                MetricTile("最高速度", RideFormat.speed(snapshot.maxKmh), Modifier.weight(1f), unit = "km/h", compact = true, valueSizeSp = 28)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            MetricTile(
                label = "走行距離",
                value = RideFormat.distance(snapshot.distanceM),
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "走行時間",
                value = RideFormat.duration(snapshot.movingTimeS),
                compact = compact,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            MetricTile(
                label = "平均速度",
                value = RideFormat.speed(snapshot.averageKmh),
                unit = "km/h",
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "最高速度",
                value = RideFormat.speed(snapshot.maxKmh),
                unit = "km/h",
                compact = compact,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            MetricTile(
                label = "獲得標高",
                value = RideFormat.elevationGain(snapshot.elevationGainM),
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 下部の操作ボタン群。
 * 開始 / 一時停止 / 再開 は手袋でも押せる高さ 64dp 以上の大きなボタン。
 * リセットと通常モードへ戻るは誤操作防止のため長押しのみ反応し、短押し時はヒントを表示する。
 */
@Composable
internal fun CycleComputerActionBar(
    state: RideState,
    isLandscape: Boolean,
    onStartPause: () -> Unit,
    onResetLongPress: () -> Unit,
    onExitMode: () -> Unit,
    modifier: Modifier = Modifier,
    onFinishAndSave: () -> Unit = onResetLongPress,
    isGpxRecording: Boolean,
) {
    val palette = CycleMapTheme.palette
    val fontScale = LocalDensity.current.fontScale
    val showFinishAndSave = shouldShowFinishAndSave(isGpxRecording)
    var hintTrigger by remember { mutableIntStateOf(0) }
    var showTapWarning by remember { mutableStateOf(false) }
    var showFinishConfirm by remember(showFinishAndSave) { mutableStateOf(false) }

    LaunchedEffect(hintTrigger) {
        if (hintTrigger > 0) {
            showTapWarning = true
            delay(2000L)
            showTapWarning = false
        }
    }

    val primaryLabel = when (state) {
        RideState.IDLE -> "開始"
        RideState.RUNNING -> "一時停止"
        RideState.PAUSED -> "再開"
    }
    val primaryContainer = when (state) {
        RideState.IDLE, RideState.PAUSED -> palette.primaryAction
        RideState.RUNNING -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val primaryContent = when (state) {
        RideState.IDLE, RideState.PAUSED -> palette.onPrimaryAction
        RideState.RUNNING -> MaterialTheme.colorScheme.onSurface
    }
    val primaryBorder = when (state) {
        RideState.IDLE, RideState.PAUSED -> null
        RideState.RUNNING -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 6.dp),
    ) {
        // 長押し操作のヒント (短押し時に強調表示する)
        Text(
            text = if (showTapWarning) {
                "長押ししてください"
            } else {
                "リセット・通常モードへ戻るは長押し"
            },
            fontSize = 13.sp,
            fontWeight = if (showTapWarning) FontWeight.Bold else FontWeight.Normal,
            color = if (showTapWarning) palette.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onStartPause,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryContainer,
                        contentColor = primaryContent,
                    ),
                    border = primaryBorder,
                    contentPadding = if (showFinishAndSave) PaddingValues(horizontal = 8.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
                    modifier = Modifier
                        .weight(if (showFinishAndSave) 1.05f else 1.3f)
                        .heightIn(min = 64.dp),
                ) {
                    Text(
                        text = primaryLabel,
                        fontSize = if (showFinishAndSave) 18.sp else 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = if (showFinishAndSave) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                }
                if (showFinishAndSave) {
                    FinishAndSaveButton(
                        label = finishAndSaveButtonLabel(fontScale),
                        shape = RoundedCornerShape(16.dp),
                        onClick = { showFinishConfirm = true },
                        modifier = Modifier.weight(1.25f),
                    )
                }
                LongPressActionButton(
                    label = "リセット",
                    onShortTap = { hintTrigger++ },
                    onLongPress = {
                        showTapWarning = false
                        onResetLongPress()
                    },
                    modifier = Modifier
                        .weight(if (showFinishAndSave) 0.75f else 0.85f)
                        .heightIn(min = 56.dp),
                )
                LongPressActionButton(
                    label = "通常へ",
                    onShortTap = { hintTrigger++ },
                    onLongPress = {
                        showTapWarning = false
                        onExitMode()
                    },
                    modifier = Modifier
                        .weight(if (showFinishAndSave) 0.75f else 0.85f)
                        .heightIn(min = 56.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onStartPause,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryContainer,
                        contentColor = primaryContent,
                    ),
                    border = primaryBorder,
                    contentPadding = if (showFinishAndSave) PaddingValues(horizontal = 8.dp, vertical = 6.dp) else ButtonDefaults.ContentPadding,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (showFinishAndSave) 64.dp else 68.dp),
                ) {
                    Text(
                        text = primaryLabel,
                        fontSize = if (showFinishAndSave) 20.sp else 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = if (showFinishAndSave) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                }
                if (showFinishAndSave) {
                    FinishAndSaveButton(
                        label = finishAndSaveButtonLabel(fontScale),
                        shape = RoundedCornerShape(18.dp),
                        onClick = { showFinishConfirm = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LongPressActionButton(
                    label = "リセット",
                    onShortTap = { hintTrigger++ },
                    onLongPress = {
                        showTapWarning = false
                        onResetLongPress()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                )
                LongPressActionButton(
                    label = if (fontScale > 1.3f) "通常へ" else "通常モードへ戻る",
                    onShortTap = { hintTrigger++ },
                    onLongPress = {
                        showTapWarning = false
                        onExitMode()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                )
            }
        }
    }

    if (showFinishAndSave && showFinishConfirm) {
        AlertDialog(
            onDismissRequest = { showFinishConfirm = false },
            title = { Text("記録を終了して保存") },
            text = { Text("記録を終えて GPX に保存します") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishConfirm = false
                        onFinishAndSave()
                    },
                ) {
                    Text("保存して終える")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirm = false }) {
                    Text("キャンセル")
                }
            },
        )
    }
}

@Composable
private fun FinishAndSaveButton(
    label: String,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = CycleMapTheme.palette
    Button(
        onClick = onClick,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.dangerContainer,
            contentColor = palette.danger,
        ),
        border = BorderStroke(1.dp, palette.danger),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 長押しのみ実行される補助ボタン。短押し時は実行せずヒント通知だけを呼び出す。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressActionButton(
    label: String,
    onShortTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "長押ししてください",
                    onLongClickLabel = label,
                    onClick = onShortTap,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "長押し",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
