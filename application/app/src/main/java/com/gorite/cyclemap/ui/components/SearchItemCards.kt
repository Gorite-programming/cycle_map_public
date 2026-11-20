package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.data.FavoriteSpot
import com.gorite.cyclemap.search.*
import com.gorite.cyclemap.utils.compassLabel8
import com.gorite.cyclemap.utils.haversineMeters
import com.gorite.cyclemap.utils.initialBearingDeg
import java.util.Locale

@Composable
internal fun SearchResultItemCard(
    result: SearchResult,
    isFavorite: Boolean,
    onResultSelected: (SearchResult) -> Unit,
    onToggleFavorite: () -> Unit,
    /** 一覧のいちばん下 (親指の近く) に置く最有力の候補なら、その理由 (「いちばん近い」など)。強調して出す。 */
    highlightLabel: String? = null,
    origin: Pair<Double, Double>? = null,
    enabled: Boolean = true,
    distanceOriginLabel: String? = null,
) {
    val distance = result.distanceMeters ?: origin?.let {
        haversineMeters(it.first, it.second, result.latitude, result.longitude)
    }
    val bearing = origin?.let {
        initialBearingDeg(it.first, it.second, result.latitude, result.longitude)
    }
    val details = listOfNotNull(
        formatCategoryLabel(result.category),
        result.address?.let { shortAddress(it) }?.takeIf { it.isNotBlank() },
    ).joinToString(" ・ ")

    if (highlightLabel == null) {
        Column(modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.35f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onResultSelected(result) }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            result.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        DistanceAndDirection(distance, bearing, originLabel = distanceOriginLabel)
                    }
                    Text(
                        details,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FavoriteButton(isFavorite, onToggleFavorite, enabled)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.35f)
            .padding(vertical = 4.dp)
            .clickable(enabled = enabled) { onResultSelected(result) },
        shape = RoundedCornerShape(12.dp),
        color = CycleMapTheme.palette.accentContainer,
        border = BorderStroke(1.5.dp, CycleMapTheme.palette.accent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(highlightLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = CycleMapTheme.palette.accent)
                Text(
                    result.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    DistanceAndDirection(distance, bearing, originLabel = distanceOriginLabel)
                }
            }
            FavoriteButton(isFavorite, onToggleFavorite, enabled)
        }
    }
}

@Composable
internal fun FavoriteSpotItemCard(
    spot: FavoriteSpot,
    onSpotSelected: (FavoriteSpot) -> Unit,
    onRemoveFavorite: () -> Unit,
    origin: Pair<Double, Double>? = null,
) {
    val distance = origin?.let {
        haversineMeters(it.first, it.second, spot.latitude, spot.longitude)
    }
    val bearing = origin?.let {
        initialBearingDeg(it.first, it.second, spot.latitude, spot.longitude)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onSpotSelected(spot) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        spot.name,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DistanceAndDirection(distance, bearing)
                }
                Text(
                    spot.address ?: formatCategoryLabel(spot.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FavoriteButton(true, onRemoveFavorite)
        }
    }
}

@Composable
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            painterResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
            contentDescription = if (isFavorite) "お気に入り解除" else "お気に入り追加",
            tint = if (isFavorite) CycleMapTheme.palette.favorite else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal const val MIN_DIRECTION_DISTANCE_METERS = 30.0

/**
 * [origin] から目的地 ([targetLat], [targetLon]) への方角 (度) を返す。
 * [origin] が無い、または 30m 未満の近距離では方角がぶれるため null を返す。
 */
internal fun directionBearingDeg(
    origin: Pair<Double, Double>?,
    targetLat: Double,
    targetLon: Double,
    distanceMeters: Double? = null,
): Double? {
    if (origin == null) return null
    if (!origin.first.isFinite() || !origin.second.isFinite() || !targetLat.isFinite() || !targetLon.isFinite()) {
        return null
    }
    if (distanceMeters != null && distanceMeters < MIN_DIRECTION_DISTANCE_METERS) return null
    if (haversineMeters(origin.first, origin.second, targetLat, targetLon) < MIN_DIRECTION_DISTANCE_METERS) {
        return null
    }
    return initialBearingDeg(origin.first, origin.second, targetLat, targetLon)
}

internal fun formatShortDistance(distanceMeters: Double): String {
    val safe = distanceMeters.coerceAtLeast(0.0)
    return if (safe >= 1000.0) {
        String.format(Locale.US, "%.1f km", safe / 1000.0)
    } else {
        String.format(Locale.US, "%.0f m", safe)
    }
}

/**
 * スポットカードの補助行ラベル: 有効な短い住所があればそれを、無ければカテゴリ表示名を返す。
 */
internal fun formatSpotSubtitle(address: String?, category: String): String {
    val short = address
        ?.takeIf { it.isNotBlank() && it.trim() != "周辺エリア" }
        ?.let { shortAddress(it) }
        ?.takeIf { it.isNotBlank() }
    return short ?: formatCategoryLabel(category)
}

@Composable
internal fun DistanceAndDirection(
    distanceMeters: Double?,
    bearingDeg: Double?,
    modifier: Modifier = Modifier.padding(start = 6.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    fontSize: TextUnit = 13.sp,
    iconSize: Dp = 14.dp,
    boldDistance: Boolean = true,
    originLabel: String? = null,
) {
    if (distanceMeters == null) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bearingDeg != null && distanceMeters >= MIN_DIRECTION_DISTANCE_METERS) {
            Icon(
                painterResource(R.drawable.ic_lucide_arrow_up),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(iconSize).rotate(bearingDeg.toFloat()),
            )
            Text(
                compassLabel8(bearingDeg),
                fontSize = fontSize,
                color = color,
                maxLines = 1,
            )
        }
        Text(
            listOfNotNull(originLabel, formatShortDistance(distanceMeters)).joinToString(" "),
            fontSize = fontSize,
            fontWeight = if (boldDistance) FontWeight.Bold else FontWeight.Normal,
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * 検索結果に添える住所の短い表記。都道府県と、重複した区名 (「広島市中区 中区袋町5-7」の 2 つ目の「中区」) を除く。
 */
internal fun shortAddress(address: String): String? {
    // 郵便番号だけの住所 (「7300036」「730-0036」) は、支店の見分けに役立たないので出さない
    if (Regex("^〒?\\s*\\d{3}-?\\d{4}$").matches(address.trim())) return null
    var a = address.trim().replace(Regex("^(広島県|岡山県|山口県|鳥取県|島根県)\\s*"), "")
    // 「◯◯市△△区 △△区…」のような重複を 1 つにする
    Regex("^(\\S*?([^\\s市]+区))\\s+\\2").find(a)?.let { m -> a = m.groupValues[1] + a.substring(m.range.last + 1) }
    return a.replace(Regex("\\s+"), " ")
}
