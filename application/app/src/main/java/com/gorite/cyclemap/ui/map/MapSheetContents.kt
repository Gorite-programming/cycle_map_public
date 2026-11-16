package com.gorite.cyclemap.ui.map

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.contract.BusinessStatus
import com.gorite.cyclemap.contract.PlaceDetail
import com.gorite.cyclemap.contract.RouteOption
import com.gorite.cyclemap.contract.TravelModeType
import com.gorite.cyclemap.ui.components.DistanceAndDirection
import com.gorite.cyclemap.ui.components.directionBearingDeg
import com.gorite.cyclemap.utils.normalizePlaceAddress

/**
 * 契約 PlaceDetail に準拠した場所詳細コンポーネント (営業中/まもなく閉店/営業時間外/不明)
 */
@Composable
fun PlaceDetailContent(
    detail: PlaceDetail,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    origin: Pair<Double, Double>? = null,
) {
    val poiCat = com.gorite.cyclemap.ui.cycling.PoiCategory.forCategory(detail.category)
        ?: com.gorite.cyclemap.ui.cycling.PoiCategory.entries.firstOrNull { it.label == detail.category }
    val catColor = poiCat?.let { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it.colorHex)) }
        ?: MaterialTheme.colorScheme.primary
    // detail.category は OSM のタグ (amenity:cafe など) か、表示用に変換済みの日本語のどちらか
    val catLabel = poiCat?.label ?: if (':' in detail.category || detail.category == "named" || detail.category == "address") {
        com.gorite.cyclemap.search.formatCategoryLabel(detail.category)
    } else detail.category

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = catColor, shadowElevation = 2.dp, modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(poiCat?.iconRes ?: R.drawable.ic_lucide_map_pin),
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(6.dp), color = catColor.copy(alpha = 0.16f)) {
                            Text(
                                text = catLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = catColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (detail.distanceMeters != null && detail.distanceMeters > 0.0) {
                            val bearingDeg = directionBearingDeg(
                                origin = origin,
                                targetLat = detail.lat,
                                targetLon = detail.lon,
                                distanceMeters = detail.distanceMeters,
                            )
                            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)) {
                                if (bearingDeg != null) {
                                    DistanceAndDirection(
                                        distanceMeters = detail.distanceMeters,
                                        bearingDeg = bearingDeg,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = MaterialTheme.typography.labelMedium.fontSize,
                                        iconSize = 13.dp,
                                        boldDistance = false,
                                    )
                                } else {
                                    Text(
                                        text = "現在地から " + formatDistance(detail.distanceMeters),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                onClick = onClose,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_x),
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 営業状態バッジ
        BusinessStatusBadge(status = detail.businessStatus)

        Spacer(modifier = Modifier.height(12.dp))
        val addrText = if (detail.address.isNotBlank() && detail.address != "周辺エリア") {
            normalizePlaceAddress(detail.address)
        } else {
            String.format(java.util.Locale.US, "周辺エリア (%.4f, %.4f)", detail.lat, detail.lon)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_map_pin),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = addrText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 営業時間・電話・Web・車椅子 (検索DB にあるものだけ)。
        val infoRows = placeInfoRows(detail)
        if (infoRows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            PlaceInfoList(infoRows)
        }
    }
}

/**
 * スポット詳細シート下部に固定表示する主ボタン行（「ここへ行く」「保存」）。
 */
@Composable
fun PlaceDetailActionBar(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onStartRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onStartRoute,
            modifier = Modifier.weight(1.35f).height(48.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(painter = painterResource(R.drawable.ic_lucide_navigation), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.go_here), fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onToggleFavorite,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(
                painter = painterResource(if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline),
                contentDescription = null,
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isFavorite) stringResource(R.string.saved_place) else stringResource(R.string.save_place))
        }
    }
}

/** 場所の情報の行 (アイコン + 文字)。電話はダイヤル画面、Web はブラウザで開く。 */
@Composable
private fun PlaceInfoList(rows: List<PlaceInfoRow>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            rows.forEach { row ->
                val icon = when (row.kind) {
                    PlaceInfoKind.HOURS -> R.drawable.ic_lucide_clock
                    PlaceInfoKind.PHONE -> R.drawable.ic_lucide_phone
                    PlaceInfoKind.WEBSITE -> R.drawable.ic_lucide_globe
                    PlaceInfoKind.WHEELCHAIR -> R.drawable.ic_lucide_accessibility
                }
                val uri = row.uri
                val linkColor = MaterialTheme.colorScheme.primary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (uri != null) Modifier.clickable {
                                val action = if (uri.startsWith("tel:")) android.content.Intent.ACTION_DIAL else android.content.Intent.ACTION_VIEW
                                try {
                                    context.startActivity(android.content.Intent(action, android.net.Uri.parse(uri)))
                                } catch (_: android.content.ActivityNotFoundException) {
                                    // 開けるアプリが無い端末では何もしない
                                }
                            } else Modifier
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uri != null) linkColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun BusinessStatusBadge(
    status: BusinessStatus
) {
    when (status) {
        is BusinessStatus.Open -> {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.status_open),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        is BusinessStatus.ClosingSoon -> {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = stringResource(R.string.status_closing_soon, status.closingTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        is BusinessStatus.Closed -> {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = stringResource(R.string.status_closed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        BusinessStatus.Unknown -> {
            // 判定不能時は何も表示しない仕様に厳格準拠
        }
    }
}


@Composable
fun RouteOptionCard(
    option: RouteOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = option.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    if (option.isPrimary) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = stringResource(R.string.recommended),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDistance(option.distanceMeters),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = "・", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = formatDuration(option.durationSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (option.elevationGainMeters > 0) {
                        Text(
                            text = "・ 獲得標高 +${option.elevationGainMeters.toInt()}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_check),
                        contentDescription = "選択中",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
