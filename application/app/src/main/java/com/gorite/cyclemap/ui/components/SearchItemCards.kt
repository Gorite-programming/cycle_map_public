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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onResultSelected(result) }
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
                        DistanceAndDirection(distance, bearing)
                    }
                    Text(
                        details,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FavoriteButton(isFavorite, onToggleFavorite)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        return
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onResultSelected(result) },
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
                    DistanceAndDirection(distance, bearing)
                }
            }
            FavoriteButton(isFavorite, onToggleFavorite)
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
private fun FavoriteButton(isFavorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            painterResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
            contentDescription = if (isFavorite) "お気に入り解除" else "お気に入り追加",
            tint = if (isFavorite) CycleMapTheme.palette.favorite else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun DistanceAndDirection(distanceMeters: Double?, bearingDeg: Double?) {
    if (distanceMeters == null) return
    Row(
        modifier = Modifier.padding(start = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bearingDeg != null && distanceMeters >= 30.0) {
            Icon(
                painterResource(R.drawable.ic_lucide_arrow_up),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp).rotate(bearingDeg.toFloat()),
            )
            Text(
                compassLabel8(bearingDeg),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        val distanceText = if (distanceMeters >= 1000.0) {
            String.format(Locale.US, "%.1f km", distanceMeters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f m", distanceMeters)
        }
        Text(
            distanceText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
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
