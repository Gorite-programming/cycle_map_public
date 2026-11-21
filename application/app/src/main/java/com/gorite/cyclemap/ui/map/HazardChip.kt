package com.gorite.cyclemap.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.hazard.HazardAhead
import com.gorite.cyclemap.hazard.HazardKind
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 注意地点用の距離を四捨五入で整形する。
 * 100 m 未満は 10 m 刻み、1000 m 未満は 50 m 刻み、1000 m 以上 (または丸め後 1000 m) は km 表記にする。
 */
internal fun formatHazardDistance(meters: Double): String {
    if (!meters.isFinite() || meters <= 0.0) return "0 m"
    if (meters < 100.0) {
        return "${(meters / 10.0).roundToInt() * 10} m"
    }
    val rounded50 = (meters / 50.0).roundToInt() * 50
    return if (rounded50 < 1000) {
        "$rounded50 m"
    } else {
        String.format(Locale.US, "%.1f km", meters / 1000.0)
    }
}

/**
 * 注意地点チップの表示文言を組み立てる。
 */
internal fun hazardChipText(ahead: HazardAhead): String {
    val distance = formatHazardDistance(targetDistanceMeters(ahead))
    return formatHazardLabel(ahead, separator = " ", distanceText = distance)
}

/**
 * TalkBack 用の読み上げ文言を組み立てる。
 */
internal fun hazardChipContentDescription(ahead: HazardAhead): String {
    val formatted = formatHazardDistance(targetDistanceMeters(ahead))
    val spokenDistance = when {
        formatted.endsWith(" km") -> formatted.removeSuffix(" km") + " キロメートル"
        formatted.endsWith(" m") -> formatted.removeSuffix(" m") + " メートル"
        else -> formatted
    }
    return formatHazardLabel(ahead, separator = "、", distanceText = spokenDistance)
}

private fun targetDistanceMeters(ahead: HazardAhead): Double =
    if (ahead.kind == HazardKind.BRIDGE && ahead.inside) ahead.remainingMeters else ahead.distanceMeters

private fun formatHazardLabel(ahead: HazardAhead, separator: String, distanceText: String): String {
    val displayName = ahead.name?.trim()?.takeIf { it.isNotEmpty() }
    return when (ahead.kind) {
        HazardKind.CROSSING -> "踏切${separator}あと $distanceText"
        HazardKind.BRIDGE -> {
            val subject = if (displayName != null) "橋 ($displayName)" else "橋"
            val verb = if (ahead.inside) "残り" else "あと"
            "$subject${separator}$verb $distanceText"
        }
    }
}

/**
 * ナビ上部カードの下に表示する、踏切・橋の控えめな案内チップ。
 */
@Composable
internal fun HazardChip(
    ahead: HazardAhead,
    modifier: Modifier = Modifier,
) {
    val palette = rememberNavPalette()
    val iconRes = when (ahead.kind) {
        HazardKind.CROSSING -> R.drawable.ic_hazard_crossing
        HazardKind.BRIDGE -> R.drawable.ic_hazard_bridge
    }
    val description = hazardChipContentDescription(ahead)

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(18.dp),
        color = palette.chip,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = palette.sub,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = hazardChipText(ahead),
                color = palette.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
