package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.data.FavoriteSpot
import com.gorite.cyclemap.search.*
import java.util.Locale

@Composable
internal fun SearchResultItemCard(
    result: SearchResult,
    isFavorite: Boolean,
    onResultSelected: (SearchResult) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onResultSelected(result) },
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
                Text(result.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // カテゴリ + 住所 (同じ名前の支店を見分けられるように)
                    Text(
                        listOfNotNull(formatCategoryLabel(result.category), result.address?.let { shortAddress(it) })
                            .joinToString(" ・ "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    result.distanceMeters?.let { dist ->
                        val distStr = if (dist >= 1000.0) {
                            String.format(Locale.US, "%.1f km", dist / 1000.0)
                        } else {
                            String.format(Locale.US, "%.0f m", dist)
                        }
                        Text(
                            distStr,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painterResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
                    contentDescription = if (isFavorite) "お気に入り解除" else "お気に入り追加",
                    tint = if (isFavorite) CycleMapTheme.palette.favorite else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun FavoriteSpotItemCard(
    spot: FavoriteSpot,
    onSpotSelected: (FavoriteSpot) -> Unit,
    onRemoveFavorite: () -> Unit,
) {
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
                Text(spot.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    spot.address ?: formatCategoryLabel(spot.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRemoveFavorite,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_star_filled),
                    contentDescription = "お気に入り解除",
                    tint = CycleMapTheme.palette.favorite,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
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
