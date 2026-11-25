package com.gorite.cyclemap.ui.cycling

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.routing.RoutePreference
import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

@Composable
internal fun RouteInfoCardContent(
    metrics: RideMetrics,
    profile: List<ElevationSample>,
    totalDistanceM: Double,
    progressDistanceM: Double?,
    showDetails: Boolean,
    onClearRoute: () -> Unit,
    modifier: Modifier = Modifier,
    tileDownloadStatus: String? = null,
    selectedPreference: RoutePreference = RoutePreference.RECOMMENDED,
    onPreferenceChange: (RoutePreference) -> Unit = {},
    routeChoices: List<RouteChoice> = emptyList(),
    selectedRouteChoice: Int = 0,
    onSelectRouteChoice: (Int) -> Unit = {},
    waypoints: List<GeoPoint> = emptyList(),
    isCalculatingRoute: Boolean = false,
    onMoveWaypoint: (Int, Int) -> Unit = { _, _ -> },
    onRemoveWaypoint: (Int) -> Unit = {},
    isDestinationFavorite: Boolean = false,
    // null なら、目的地をお気に入りにするボタンを出さない
    onToggleDestinationFavorite: (() -> Unit)? = null,
    offlineReadiness: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_lucide_navigation),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("ルートプレビュー", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metrics.remainingM?.let { formatRouteDistance(it) } ?: "--",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (onToggleDestinationFavorite != null) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onToggleDestinationFavorite),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(if (isDestinationFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
                                contentDescription = if (isDestinationFavorite) "目的地をお気に入りから外す" else "目的地をお気に入りに追加",
                                tint = if (isDestinationFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClearRoute),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_lucide_x),
                            contentDescription = "ルートを消去",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (waypoints.isNotEmpty()) {
            WaypointList(
                waypoints = waypoints,
                isEnabled = !isCalculatingRoute,
                onMove = onMoveWaypoint,
                onRemove = onRemoveWaypoint,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        // 条件はつながったピルにまとめ、下の経路候補と区別する。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(2.dp),
        ) {
            RoutePreference.entries.forEach { pref ->
                val isSelected = pref == selectedPreference
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onPreferenceChange(pref) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            pref.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // 経路の候補 (2 本以上あるとき): 選ぶと地図の主線と案内がその経路になる
        if (routeChoices.size >= 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                routeChoices.forEachIndexed { index, choice ->
                    val isSelected = index == selectedRouteChoice
                    // 選んでいる候補は、見出しと同じ距離 (出発地・目的地までの線を含む) で出す
                    val shownDistanceM = if (isSelected && totalDistanceM.isFinite() && totalDistanceM > 0.0) totalDistanceM else choice.distanceMeters
                    val shownDetail = formatRouteDistance(shownDistanceM) + choice.detail.removePrefix(formatRouteDistance(choice.distanceMeters))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectRouteChoice(index) },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    if (isSelected) "${choice.label} ✓" else choice.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                choice.featureTag?.let { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                    ) {
                                        Text(
                                            tag,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                formatRemainingTime(choice.copy(distanceMeters = shownDistanceM).estimatedDurationSeconds(metrics.averageKmh)),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                shownDetail,
                                fontSize = 12.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            choice.elevationDiffText?.let { diffText ->
                                Text(
                                    diffText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        offlineReadiness()

        // 基本指標セル (残り時間 / 予想平均速度 / 獲得標高)
        if (showDetails || routeChoices.size <= 1) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            RouteStatCell(
                R.drawable.ic_lucide_clock,
                "予想時間",
                metrics.remainingS?.let { formatRemainingTime(it) } ?: "--",
                Modifier.weight(1f),
            )
            RouteStatCell(
                R.drawable.ic_lucide_gauge,
                "平均速度",
                metrics.averageKmh?.let { "%.1fkm/h".format(java.util.Locale.US, it) } ?: "--",
                Modifier.weight(1f),
            )
            RouteStatCell(
                R.drawable.ic_lucide_trending_up,
                "登り合計",
                metrics.elevationGainM?.let { "${it.roundToInt()}m" } ?: "—",
                Modifier.weight(1f),
            )
        }

        if (showDetails && profile.size >= 2) {
            ElevationProfileChart(
                profile = profile,
                totalDistanceM = totalDistanceM,
                progressDistanceM = progressDistanceM,
                lineColor = MaterialTheme.colorScheme.primary,
                passedColor = MaterialTheme.colorScheme.secondary,
                gridColor = MaterialTheme.colorScheme.outlineVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "標高プロファイル（${elevationSourceLabel(profile)}）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
        } else if (showDetails) {
            Text(
                "標高データなし",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        if (tileDownloadStatus != null) {
            Text(
                tileDownloadStatus,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
internal fun RoutePreviewActions(
    onStart: () -> Unit,
    onDownloadCorridorTiles: () -> Unit,
    onExportGpx: () -> Unit,
) {
    // 出発前に、曲がる角を順に確認できる (Google マップの「ステップ」一覧)
    val instructions = com.gorite.cyclemap.ui.components.LocalRouteInstructions.current
    var showSteps by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (instructions.isNotEmpty()) {
        androidx.compose.material3.TextButton(onClick = { showSteps = true }) {
            Text("曲がり角の一覧を見る", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (showSteps) {
        val steps = androidx.compose.runtime.remember(instructions) {
            com.gorite.cyclemap.navigation.upcomingSteps(instructions, 0.0, limit = 200)
        }
        com.gorite.cyclemap.ui.components.StepListDialog(
            title = "この経路の曲がり角",
            steps = steps,
            emptyText = "曲がる角はありません",
            onDismiss = { showSteps = false },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedIconButton(
            onClick = onDownloadCorridorTiles,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Icon(
                painterResource(R.drawable.ic_lucide_download),
                contentDescription = "この経路の地図を保存",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        OutlinedIconButton(
            onClick = onExportGpx,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "GPXで保存" },
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(
                "GPX",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // サイコン画面で走る: ナビを始めてから、地図の代わりに速度・距離・時間の画面にする。
        val displayModeController = com.gorite.cyclemap.ui.state.LocalDisplayModeController.current
        if (displayModeController != null) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    onStart()
                    displayModeController.set(com.gorite.cyclemap.ui.state.DisplayMode.CYCLE_COMPUTER)
                },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) {
                Text("サイコン", fontSize = 12.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Button(
            onClick = onStart,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                painterResource(R.drawable.ic_lucide_navigation),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("ナビ開始", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun GradePill(label: String, grade: Double, modifier: Modifier = Modifier) {
    val level = gradeLevelOf(grade)
    val iconRes = when (level) {
        GradeLevel.STEEP_UP, GradeLevel.GENTLE_UP -> R.drawable.ic_lucide_trending_up
        GradeLevel.DOWN -> R.drawable.ic_lucide_trending_down
        GradeLevel.FLAT -> R.drawable.ic_lucide_minus
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = level.label,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "${formatGrade(grade)} · ${level.label}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun RouteStatCell(iconRes: Int, label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
    }
}
