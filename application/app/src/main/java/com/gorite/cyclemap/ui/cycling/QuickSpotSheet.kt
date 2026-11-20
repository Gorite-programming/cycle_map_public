package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.data.FavoriteSpot
import com.gorite.cyclemap.data.FavoritesManager
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.routing.RouteProgressIndex
import com.gorite.cyclemap.search.activeRemainingRoutePoints
import com.gorite.cyclemap.search.findMapViewModelOrNull
import com.gorite.cyclemap.ui.components.DistanceAndDirection
import com.gorite.cyclemap.ui.components.directionBearingDeg
import com.gorite.cyclemap.ui.components.formatShortDistance
import com.gorite.cyclemap.ui.components.formatSpotSubtitle
import kotlin.math.roundToInt

/**
 * MapScreen上部等に配置するクイックスポットフィルタの水平ピルボタン列。
 * サイクリストが走行中や停止時にグローブを装着していてもワンタップで押しやすい形状。
 */
@Composable
fun QuickSpotFilterRow(
    selectedType: QuickSpotType?,
    onSelectType: (QuickSpotType) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding,
    ) {
        items(QuickSpotType.entries, key = { it.name }) { type ->
            val isSelected = selectedType == type
            QuickSpotPill(
                type = type,
                isSelected = isSelected,
                onClick = { onSelectType(type) },
            )
        }
    }
}

@Composable
fun QuickSpotPill(
    type: QuickSpotType,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isSelected) CycleMapTheme.palette.accentContainer else MaterialTheme.colorScheme.surface
    val contentColor = if (isSelected) CycleMapTheme.palette.accent else CycleMapTheme.palette.mapControlIcon
    val borderStroke = if (isSelected) {
        BorderStroke(1.5.dp, CycleMapTheme.palette.accent)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = borderStroke,
        shadowElevation = if (isSelected) 4.dp else 2.dp,
        modifier = modifier.height(38.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(type.iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = type.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
            )
        }
    }
}

/**
 * ルートからの距離を添えたクイックスポット表示項目。
 */
internal data class QuickSpotWithRouteDistance(
    val spot: NearbySpot,
    val routeDistanceMeters: Double?,
)

/**
 * ナビ中は「経路に沿った先までの距離 + 経路から外れる往復の距離」の短い順 (同じなら現在地に近い順) に並べ替える。
 * ルート点が 2 点未満のときは元の順序を保つ。
 */
internal fun sortSpotsByRouteProximity(
    spots: List<NearbySpot>,
    routePoints: List<RoutePoint>,
): List<QuickSpotWithRouteDistance> {
    if (routePoints.size < 2 || spots.isEmpty()) {
        return spots.map { QuickSpotWithRouteDistance(it, null) }
    }
    val index = RouteProgressIndex(routePoints)
    // 実質距離 = 経路に沿った先までの距離 + 経路から外れて戻る往復の迂回距離。
    // 先の遠い店が「ルート直上」というだけで、すぐ先の店より上位に来ないようにする。
    return spots
        .map { spot ->
            val progress = index.calculate(RoutePoint(spot.latitude, spot.longitude))
            val routeDist = progress?.distanceToRouteMeters
            val effective = progress?.let { it.distanceFromStartMeters + 2.0 * it.distanceToRouteMeters }
            Triple(QuickSpotWithRouteDistance(spot, routeDist), effective ?: Double.POSITIVE_INFINITY, spot.distanceM)
        }
        .sortedWith(compareBy<Triple<QuickSpotWithRouteDistance, Double, Double>>({ it.second }, { it.third }))
        .map { it.first }
}

/** 走行距離を200m単位に丸め、GPS更新ごとの経路再生成・候補ソートを避ける。 */
internal fun quickSpotProgressKey(traveledMeters: Double?): Long =
    if (traveledMeters == null || !traveledMeters.isFinite() || traveledMeters <= 0.0) 0L
    else kotlin.math.floor(traveledMeters / 200.0).toLong()

internal fun formatDistanceFromRoute(meters: Double): String {
    if (!meters.isFinite() || meters < 0.0) return ""
    val rounded = meters.roundToInt()
    return if (rounded < 1000) "ルートから${rounded}m" else "ルートから${"%.1f".format(java.util.Locale.US, meters / 1000.0)}km"
}

/**
 * 経路から外れて立ち寄り、再び経路へ戻る往復の追加距離 (片道距離 × 2) を既存の距離書式で返す。
 */
internal fun formatDetourDistance(routeDistanceMeters: Double): String {
    if (!routeDistanceMeters.isFinite() || routeDistanceMeters <= 0.0) return ""
    return "迂回+${formatShortDistance(routeDistanceMeters * 2.0)}"
}

/**
 * 選択された QuickSpotType の周辺スポット一覧を表示するボトムシート。
 * 距離、スポット名、カテゴリバッジ、および「ここへ行く / 立ち寄る」ボタンを備える。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSpotBottomSheet(
    selectedType: QuickSpotType,
    onSelectType: (QuickSpotType) -> Unit,
    spots: List<NearbySpot>,
    isLoading: Boolean,
    errorMessage: String? = null,
    onSpotClick: (NearbySpot) -> Unit,
    onRouteClick: (NearbySpot) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    origin: Pair<Double, Double>? = null,
    isNavigating: Boolean = false,
    routePoints: List<RoutePoint> = emptyList(),
    navigationProgress: RouteProgress? = null,
) {
    val context = LocalContext.current
    val mapViewModel = remember(context) { findMapViewModelOrNull(context) }
    val effectiveProgress = navigationProgress ?: if (isNavigating) mapViewModel?.navigation?.progress?.value else null
    val progressKey = quickSpotProgressKey(effectiveProgress?.distanceFromStartMeters)
    val effectiveRoutePoints = remember(isNavigating, routePoints, progressKey) {
        activeRemainingRoutePoints(isNavigating, routePoints, effectiveProgress)
    }
    val favoritesManager = remember(context) { FavoritesManager.getInstance(context) }
    val favorites by favoritesManager.favorites.collectAsState()
    val sortedByRoute = isNavigating && effectiveRoutePoints.size >= 2
    val displayItems = remember(spots, sortedByRoute, effectiveRoutePoints) {
        if (sortedByRoute) sortSpotsByRouteProximity(spots, effectiveRoutePoints)
        else spots.map { QuickSpotWithRouteDistance(it, null) }
    }

    // ステータスバーより下で止める (端末がダーク設定でアプリがライトのとき、白い時計が明るいシートに溶けるため)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ヘッダー行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = CycleMapTheme.palette.accentContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(selectedType.iconRes),
                                contentDescription = null,
                                tint = CycleMapTheme.palette.accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "周辺の${selectedType.label}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (displayItems.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = CycleMapTheme.palette.accentContainer,
                                ) {
                                    Text(
                                        text = "${displayItems.size}件",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CycleMapTheme.palette.accent,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = when {
                                sortedByRoute -> "ルートに近い順に表示・経由地や目的地に設定"
                                isNavigating -> "最寄りから順に表示・経由地や目的地に設定"
                                else -> "最寄りから順に表示・ワンタップでルート案内"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_x),
                        contentDescription = "閉じる",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // カテゴリ切り替えピル行 (シート内でも素早く切替可能)
            QuickSpotFilterRow(
                selectedType = selectedType,
                onSelectType = onSelectType,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 0.dp),
            )

            HorizontalDivider()

            // スポット一覧エリア
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = cyclingAccent,
                            )
                            Text(
                                text = "周辺の${selectedType.label}を検索中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                displayItems.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(selectedType.iconRes),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = "周辺に${selectedType.label}は見つかりませんでした\n別のカテゴリを選択するか、地図を移動してください",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            displayItems,
                            key = { "${it.spot.name}:${it.spot.latitude}:${it.spot.longitude}" },
                        ) { item ->
                            val spot = item.spot
                            val isFav = favorites.any {
                                kotlin.math.abs(it.latitude - spot.latitude) < 0.0001 &&
                                    kotlin.math.abs(it.longitude - spot.longitude) < 0.0001
                            }
                            QuickSpotCard(
                                spot = spot,
                                isFavorite = isFav,
                                onToggleFavorite = {
                                    favoritesManager.toggleFavorite(
                                        FavoriteSpot(
                                            id = "${spot.name}_${spot.latitude}_${spot.longitude}",
                                            name = spot.name,
                                            category = spot.category,
                                            latitude = spot.latitude,
                                            longitude = spot.longitude,
                                            address = spot.address,
                                        ),
                                    )
                                },
                                onSpotClick = { onSpotClick(spot) },
                                onRouteClick = { onRouteClick(spot) },
                                origin = origin,
                                routeDistanceMeters = item.routeDistanceMeters,
                                routeActionLabel = if (isNavigating) "立ち寄る" else "ここへ行く",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickSpotCard(
    spot: NearbySpot,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSpotClick: () -> Unit,
    onRouteClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    origin: Pair<Double, Double>? = null,
    distanceMeters: Double? = spot.distanceM,
    routeDistanceMeters: Double? = null,
    routeActionLabel: String = "ここへ行く",
) {
    val bearingDeg = directionBearingDeg(
        origin = origin,
        targetLat = spot.latitude,
        targetLon = spot.longitude,
        distanceMeters = distanceMeters,
    )
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSpotClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 上段: 施設名 (最大2行) + 右端に方角と距離
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = spot.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DistanceAndDirection(
                    distanceMeters = distanceMeters,
                    bearingDeg = bearingDeg,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }

            // 下段: 左に短い住所 (あれば) または カテゴリ (+ ルートからの距離)、右に ☆ (48dp) と「ここへ行く / 立ち寄る」
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val baseSubtitle = formatSpotSubtitle(spot.address, spot.category)
                val routeDistLabel = routeDistanceMeters?.let(::formatDistanceFromRoute).orEmpty()
                val detourLabel = routeDistanceMeters?.let(::formatDetourDistance).orEmpty()
                val routeInfoLabel = listOf(routeDistLabel, detourLabel)
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                val subtitleText = when {
                    routeInfoLabel.isNotEmpty() && baseSubtitle.isNotEmpty() -> "$routeInfoLabel ・ $baseSubtitle"
                    routeInfoLabel.isNotEmpty() -> routeInfoLabel
                    else -> baseSubtitle
                }
                Text(
                    text = subtitleText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
                            contentDescription = if (isFavorite) "お気に入り解除" else "お気に入り追加",
                            tint = if (isFavorite) CycleMapTheme.palette.favorite else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    if (onRouteClick != null) {
                        Button(
                            onClick = onRouteClick,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CycleMapTheme.palette.accent,
                                contentColor = CycleMapTheme.palette.onAccent,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_navigation),
                                contentDescription = null,
                                tint = CycleMapTheme.palette.onAccent,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = routeActionLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = CycleMapTheme.palette.onAccent,
                            )
                        }
                    }
                }
            }
        }
    }
}
