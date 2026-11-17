package com.gorite.cyclemap.ui.cycling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.tracking.GpxParser
import com.gorite.cyclemap.tracking.RideHistorySummary
import com.gorite.cyclemap.ui.theme.CycleMapTheme
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

val CyclingCyan = Color(0xFF00E5FF)

private sealed interface RideHistoryLoadState {
    data object Loading : RideHistoryLoadState
    data class Loaded(val rides: List<RideHistorySummary>) : RideHistoryLoadState
}

internal data class EmptyRideHistoryGuide(
    val title: String,
    val steps: String,
    val caption: String,
)

internal data class RideHistoryTotals(
    val monthCount: Int,
    val monthDistanceMeters: Double,
    val totalCount: Int,
    val totalDistanceMeters: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistorySheet(
    isRecording: Boolean,
    pointCount: Int,
    statusMessage: String? = null,
    rideSummary: String?,
    gpxDir: File,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onShowTrackOnMap: (RideHistorySummary) -> Unit,
    onDeleteRide: ((RideHistorySummary) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val loadState by androidx.compose.runtime.produceState<RideHistoryLoadState>(
        initialValue = RideHistoryLoadState.Loading,
        isRecording,
        refreshTrigger,
        gpxDir,
    ) {
        if (value !is RideHistoryLoadState.Loaded) {
            value = RideHistoryLoadState.Loading
        }
        val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!gpxDir.exists()) emptyList()
            else {
                gpxDir.listFiles { file -> file.extension.lowercase() == "gpx" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.mapNotNull { GpxParser.parse(it) }
                    ?: emptyList()
            }
        }
        value = RideHistoryLoadState.Loaded(parsed)
    }

    var selectedRide by remember { mutableStateOf<RideHistorySummary?>(null) }
    var rideToDelete by remember { mutableStateOf<RideHistorySummary?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN) }

    // ステータスバーより下で止める (端末がダーク設定でアプリがライトのとき、白い時計が明るいシートに溶けるため)
    ModalBottomSheet(
        modifier = Modifier.statusBarsPadding(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 内容が長いときもタブバーを押し出さないよう、残りの高さの中でスクロールさせる
                .weight(1f, fill = false)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("走行記録 (GPX)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SheetCloseButton(onClick = onDismiss)
            }

            // 現在の記録ステータスカード
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRecording) cyclingAccent.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                if (isRecording) "記録中..." else "未記録",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isRecording) cyclingAccent else MaterialTheme.colorScheme.onSurface,
                            )
                            if (isRecording) {
                                Text("$pointCount 地点取得", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (isRecording) {
                            Button(
                                onClick = onStopRecording,
                                colors = ButtonDefaults.buttonColors(containerColor = cyclingAccent),
                            ) {
                                Text("記録停止・保存")
                            }
                        } else {
                            Button(onClick = onStartRecording) {
                                Text("記録開始")
                            }
                        }
                    }
                    statusMessage?.let { message ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.contains("記録しませんでした")) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    rideSummary?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 過去のライド履歴セクション
            val loadedRides = (loadState as? RideHistoryLoadState.Loaded)?.rides
            Text(
                historySectionTitle(loadedRides?.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            when (val state = loadState) {
                is RideHistoryLoadState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = CycleMapTheme.palette.accent,
                        )
                    }
                }

                is RideHistoryLoadState.Loaded -> {
                    val histories = state.rides
                    if (histories.isEmpty()) {
                        val guide = buildEmptyRideHistoryGuide(isRecording)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = guide.title,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = guide.steps,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = guide.caption,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        val totals = remember(histories) { summarizeRideHistory(histories) }
                        Text(
                            text = formatRideHistoryTotalsSummary(totals),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(histories) { ride ->
                                RideHistoryRow(
                                    ride = ride,
                                    dateText = dateFormat.format(ride.startTime),
                                    onSelect = { selectedRide = ride },
                                    onDelete = { rideToDelete = ride },
                                )
                            }
                        }
                    }
                }
            }
        }
        SheetNavTabBar(selected = GoogleNavTab.RECORD)
      }
    }

    // 削除確認ダイアログ
    rideToDelete?.let { ride ->
        AlertDialog(
            onDismissRequest = { rideToDelete = null },
            title = { Text("走行記録の削除", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${dateFormat.format(ride.startTime)} の走行ログ (${"%.2f".format(Locale.US, ride.totalDistanceMeters / 1000.0)} km) を削除しますか？\n削除したファイルは元に戻せません。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDel = ride
                        rideToDelete = null
                        if (selectedRide?.file == toDel.file) {
                            selectedRide = null
                        }
                        toDel.file.delete()
                        onDeleteRide?.invoke(toDel)
                        refreshTrigger++
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { rideToDelete = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

    // ライド詳細ダイアログ
    selectedRide?.let { ride ->
        RideDetailDialog(
            ride = ride,
            onShowOnMap = {
                onShowTrackOnMap(ride)
                selectedRide = null
                onDismiss()
            },
            onDelete = {
                rideToDelete = ride
            },
            onDismiss = { selectedRide = null },
        )
    }
}

@Composable
private fun RideHistoryRow(
    ride: RideHistorySummary,
    dateText: String,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RideHistoryMetricCell(
                        label = "距離",
                        value = formatRideDistance(ride.totalDistanceMeters),
                        valueColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    RideHistoryMetricCell(
                        label = "経過時間",
                        value = formatDuration(ride.totalDurationSeconds),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RideHistoryMetricCell(
                        label = "平均速度",
                        value = formatRideSpeed(ride.averageSpeedKmh),
                        valueColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    RideHistoryMetricCell(
                        label = "獲得標高",
                        value = formatElevationGain(ride),
                        valueColor = if (hasElevationData(ride)) {
                            CycleMapTheme.palette.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.ic_lucide_trash_2),
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
            Icon(
                painterResource(R.drawable.ic_lucide_arrow_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun RideHistoryMetricCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun RideDetailDialog(
    ride: RideHistorySummary,
    onShowOnMap: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.JAPAN) }
    val elevationColor = if (hasElevationData(ride)) {
        CycleMapTheme.palette.success
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("ライド詳細", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    dateFormat.format(ride.startTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 主要 4 指標を 2 列 × 2 段で均等幅表示
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(
                        label = "総距離",
                        value = formatRideDistance(ride.totalDistanceMeters),
                        accentColor = CycleMapTheme.palette.accent,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "経過時間",
                        value = formatDuration(ride.totalDurationSeconds),
                        accentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(
                        label = "平均速度",
                        value = formatRideSpeed(ride.averageSpeedKmh),
                        accentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "獲得標高",
                        value = formatElevationGain(ride),
                        accentColor = elevationColor,
                        modifier = Modifier.weight(1f),
                    )
                }

                Text(
                    text = "平均速度は止まっていた時間を含みます",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatTile(
                        label = "最高速度",
                        value = formatRideSpeed(ride.maxSpeedKmh),
                        accentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        valueFontSize = 14.sp,
                        verticalPadding = 8.dp,
                    )
                    StatTile(
                        label = "地点数",
                        value = "${ride.points.size} 点",
                        accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        valueFontSize = 14.sp,
                        verticalPadding = 8.dp,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 速度推移グラフ
                Text("速度プロファイル (km/h)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (ride.points.size >= 2) {
                    SpeedProfileChart(
                        points = ride.points,
                        maxSpeed = (ride.maxSpeedKmh * 1.1).coerceAtLeast(20.0),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(90.dp),
                    )
                } else {
                    Text("データ不足", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(painterResource(R.drawable.ic_lucide_trash_2), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("削除")
                }
                Button(onClick = onShowOnMap) {
                    Icon(painterResource(R.drawable.ic_lucide_map), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("地図に表示")
                }
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    runCatching {
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.gpx", ride.file)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/gpx+xml"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            clipData = android.content.ClipData.newRawUri("GPX", uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "走行記録を共有"))
                    }.onFailure {
                        android.widget.Toast.makeText(context, "共有できませんでした", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("共有") }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        },
    )
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    valueFontSize: TextUnit = 17.sp,
    verticalPadding: Dp = 10.dp,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = valueFontSize,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpeedProfileChart(
    points: List<com.gorite.cyclemap.tracking.GpxTrackPoint>,
    maxSpeed: Double,
    modifier: Modifier = Modifier,
) {
    val lineColor = cyclingAccent2
    val chartBackground = if (CycleMapTheme.palette.isDark) com.gorite.cyclemap.ui.theme.SumiShu.Sumi2 else Color(0xFF1E2430)
    Canvas(modifier = modifier.background(chartBackground, RoundedCornerShape(8.dp))) {
        val w = size.width
        val h = size.height
        val pad = 12f

        val totalDist = points.last().distanceMetersFromStart.coerceAtLeast(1.0)
        val path = Path()

        points.forEachIndexed { i, p ->
            val x = pad + (p.distanceMetersFromStart / totalDist).toFloat() * (w - 2 * pad)
            val y = (h - pad) - (p.speedKmh / maxSpeed).toFloat().coerceIn(0f, 1f) * (h - 2 * pad)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

internal fun historySectionTitle(rideCount: Int?): String =
    if (rideCount == null) "過去のライド履歴" else "過去のライド履歴 (${rideCount}件)"

internal fun buildEmptyRideHistoryGuide(isRecording: Boolean): EmptyRideHistoryGuide =
    EmptyRideHistoryGuide(
        title = "最初のライドを記録しましょう",
        steps = if (isRecording) {
            "止めると履歴に追加されます"
        } else {
            "記録開始 → 走る → 記録を止めると保存されます"
        },
        caption = "保存すると距離・時間・平均速度・獲得標高が見られます",
    )

internal fun summarizeRideHistory(
    rides: List<RideHistorySummary>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): RideHistoryTotals {
    val currentMonth = YearMonth.from(today)
    var monthCount = 0
    var monthDistance = 0.0
    var totalDistance = 0.0
    for (ride in rides) {
        val dist = ride.totalDistanceMeters.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        totalDistance += dist
        val rideDate = Instant.ofEpochMilli(ride.startTime.time).atZone(zone).toLocalDate()
        if (YearMonth.from(rideDate) == currentMonth) {
            monthCount++
            monthDistance += dist
        }
    }
    return RideHistoryTotals(
        monthCount = monthCount,
        monthDistanceMeters = monthDistance,
        totalCount = rides.size,
        totalDistanceMeters = totalDistance,
    )
}

internal fun formatRideHistoryTotalsSummary(totals: RideHistoryTotals): String {
    val monthKm = "%.1f".format(Locale.US, totals.monthDistanceMeters.coerceAtLeast(0.0) / 1000.0)
    val monthText = "今月 ${totals.monthCount} 回 ・ $monthKm km"
    if (totals.totalCount <= totals.monthCount) {
        return monthText
    }
    val totalKm = "%.1f".format(Locale.US, totals.totalDistanceMeters.coerceAtLeast(0.0) / 1000.0)
    return "$monthText（通算 ${totals.totalCount} 回 ・ $totalKm km）"
}

internal fun formatRideDistance(distanceMeters: Double): String {
    val safe = distanceMeters.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    return "%.2f km".format(Locale.US, safe / 1000.0)
}

internal fun formatRideSpeed(speedKmh: Double): String {
    val safe = speedKmh.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    return "%.1f km/h".format(Locale.US, safe)
}

internal fun hasElevationData(ride: RideHistorySummary): Boolean {
    if (ride.elevationGainMeters.isFinite() && ride.elevationGainMeters > 0.0) return true
    return ride.points.zipWithNext().any { (prev, curr) ->
        prev.elevationMeters != null && curr.elevationMeters != null
    }
}

internal fun formatElevationGain(ride: RideHistorySummary): String {
    if (!hasElevationData(ride)) return "—"
    val safe = ride.elevationGainMeters.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    return "+%.0f m".format(Locale.US, safe)
}

internal fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%02d:%02d".format(Locale.US, m, s)
}
