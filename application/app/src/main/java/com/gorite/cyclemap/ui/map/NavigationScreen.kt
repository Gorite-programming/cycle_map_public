package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gorite.cyclemap.R


@Composable
fun GoogleNavHeaderView(
    currentStep: com.gorite.cyclemap.contract.GuidanceStep?,
    nextStep: com.gorite.cyclemap.contract.GuidanceStep?,
    laneHints: List<com.gorite.cyclemap.contract.LaneInfo>,
    isRerouting: Boolean,
    animationEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    GoogleNavHeaderV2(
        currentStep = currentStep,
        nextStep = nextStep,
        laneHints = laneHints,
        isRerouting = isRerouting,
        animationEnabled = animationEnabled,
        modifier = modifier
    )
}

@Composable
fun GoogleNavFooterView(
    eta: com.gorite.cyclemap.contract.EtaInfo?,
    remainingDistanceMeters: Double,
    remainingSeconds: Double,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    GoogleNavFooterV2(
        eta = eta,
        remainingDistanceMeters = remainingDistanceMeters,
        remainingSeconds = remainingSeconds,
        isMuted = isMuted,
        onToggleMute = onToggleMute,
        onStopNavigation = onStopNavigation,
        modifier = modifier
    )
}

@Composable
fun GoogleNavHeaderView(
    distanceToTurnMeters: Double,
    instructionText: String,
    streetName: String?,
    lanes: List<String> = emptyList(),
    animationEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CycleMapTheme.palette.navBanner,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            // 上部UIの最上段 NEXT>>> アニメーションバナー
            NextFlowAnimation(
                animationEnabled = animationEnabled,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 大矢印アイコン
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_navigation),
                    contentDescription = null,
                    tint = CycleMapTheme.palette.onNavBanner,
                    modifier = Modifier.size(36.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatDistance(distanceToTurnMeters),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = CycleMapTheme.palette.onNavBanner
                    )
                    Text(
                        text = instructionText,
                        style = MaterialTheme.typography.titleMedium,
                        color = CycleMapTheme.palette.onNavBanner
                    )
                    if (!streetName.isNullOrEmpty()) {
                        Text(
                            text = streetName,
                            style = MaterialTheme.typography.bodySmall,
                            color = CycleMapTheme.palette.onNavBannerSub
                        )
                    }
                }
            }

            // 車線案内バー (lanes)
            if (lanes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(start = 52.dp)
                ) {
                    lanes.forEach { lane ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CycleMapTheme.palette.onNavBanner.copy(alpha = 0.2f),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = lane,
                                style = MaterialTheme.typography.labelSmall,
                                color = CycleMapTheme.palette.onNavBanner,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GoogleNavFooterView(
    remainingDistanceMeters: Double,
    remainingSeconds: Double,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = formatEta(remainingSeconds),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CycleMapTheme.palette.etaText
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formatDistance(remainingDistanceMeters),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "・ " + formatDuration(remainingSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ミュート切替
                IconButton(onClick = onToggleMute) {
                    Icon(
                        painter = painterResource(if (isMuted) R.drawable.ic_lucide_volume_x else R.drawable.ic_lucide_volume_2),
                        contentDescription = if (isMuted) "ミュート中" else "音声ON",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // ナビ終了ボタン
                Button(
                    onClick = onStopNavigation,
                    colors = ButtonDefaults.buttonColors(containerColor = CycleMapTheme.palette.danger),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("終了", color = CycleMapTheme.palette.onDanger, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.toInt()} m"
    } else {
        String.format("%.1f km", meters / 1000.0)
    }
}

fun formatDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}日${hours % 24}時間"
        hours > 0 -> "${hours}時間${minutes}分"
        else -> "${minutes}分"
    }
}

/**
 * 到着予定時刻 (エポックミリ秒) を "HH:mm" に整形する。
 * 最も近い分 (30 秒以上は繰り上げ) に丸め、23:59:30 以降は翌日 "00:00" とする。
 * 上段 (LandscapeNavHudBar) と下段 (GoogleNavFooterV2 / NavBottomPanelC) で共通利用する。
 */
internal fun formatArrivalClock(
    epochMillis: Long,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    val zoned = java.time.Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    val rounded = if (zoned.second >= 30) {
        zoned.plusMinutes(1).withSecond(0).withNano(0)
    } else {
        zoned.withSecond(0).withNano(0)
    }
    return String.format(java.util.Locale.US, "%02d:%02d", rounded.hour, rounded.minute)
}

internal fun formatEta(
    seconds: Double,
    nowEpochMillis: Long,
    zoneId: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    val deltaMillis = if (seconds.isFinite() && seconds > 0.0) kotlin.math.round(seconds * 1000.0).toLong() else 0L
    return "${formatArrivalClock(nowEpochMillis + deltaMillis, zoneId)} 到着"
}

fun formatEta(seconds: Double): String = formatEta(seconds, System.currentTimeMillis())

/**
 * 走行統計から、横画面の上段・下段が共有する到着予測を作る。
 * 到着時刻は統計の etaEpochMillis をそのまま使う (縦画面の数値パネルと同じ値)。速度が得られず
 * 算出できないとき (到着時刻が null、残り時間が有限でない) は null を返し、呼び出し側は「計算中」を出す。
 */
internal fun etaInfoFromNavStats(
    stats: com.gorite.cyclemap.routing.NavigationStats,
    currentSpeedKmh: Double,
): com.gorite.cyclemap.contract.EtaInfo? {
    val arrival = stats.etaEpochMillis ?: return null
    if (!stats.durationRemainingSeconds.isFinite()) return null
    return com.gorite.cyclemap.contract.EtaInfo(
        remainingDistanceMeters = stats.remainingMeters,
        remainingSeconds = stats.durationRemainingSeconds,
        arrivalEpochMillis = arrival,
        currentSpeedKmh = currentSpeedKmh,
    )
}
