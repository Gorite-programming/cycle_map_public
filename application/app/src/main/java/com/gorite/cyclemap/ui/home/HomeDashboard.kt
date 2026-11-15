package com.gorite.cyclemap.ui.home

import android.location.Location
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.gorite.cyclemap.tracking.GpxParser
import com.gorite.cyclemap.ui.cycling.QuickSpotType
import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.weather.WeatherCategory
import com.gorite.cyclemap.weather.WeatherUiState
import com.gorite.cyclemap.weather.weatherTint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** 今日と今週 (月曜はじまり) の走行距離 (m)。GPX の記録から数える。 */
internal data class RideTotals(val todayMeters: Double, val weekMeters: Double)

/** 記録の開始時刻 (epoch ms) と距離 (m) の組から、今日・今週の合計を出す。 */
internal fun sumRideTotals(
    rides: List<Pair<Long, Double>>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): RideTotals {
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    var day = 0.0
    var week = 0.0
    for ((startMillis, meters) in rides) {
        val date = java.time.Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
        if (date == today) day += meters
        if (!date.isBefore(weekStart) && !date.isAfter(today)) week += meters
    }
    return RideTotals(day, week)
}

/** 待ち受けの盤面 (UI 総合版の「弐 計器盤」)。地図の下に重ね、押す操作はすべて下に集める。 */
@Composable
internal fun HomeDashboard(
    locationName: String,
    currentLocation: Location?,
    weatherUiState: WeatherUiState,
    onRefreshWeather: () -> Unit,
    isRecording: Boolean,
    gpxPointCount: Int,
    onToggleRecording: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorite: (FavoriteSpot) -> Unit,
    onQuickSpot: (QuickSpotType) -> Unit,
    onOpenRecords: () -> Unit,
    onOpenSaved: () -> Unit,
    modifier: Modifier = Modifier,
    /** ナビゲーションバーの高さ。盤面の地をその下まで伸ばし、中身はその上に置く。 */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    val pal = CycleMapTheme.palette
    val favorites by remember(context) { FavoritesManager.getInstance(context) }.favorites.collectAsState()

    // 記録が止まるたびに数え直す (記録中の分は止めたときに加わる)
    val totals by produceState(RideTotals(0.0, 0.0), isRecording) {
        value = withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "CycleMap/gpx")
            val rides = dir.listFiles { f -> f.extension.lowercase() == "gpx" }
                ?.mapNotNull { GpxParser.parse(it) }
                ?.map { it.startTime.time to it.totalDistanceMeters }
                .orEmpty()
            sumRideTotals(rides, LocalDate.now())
        }
    }
    val weather = when (weatherUiState) {
        is WeatherUiState.Fresh -> weatherUiState.data
        is WeatherUiState.Stale -> weatherUiState.data
        is WeatherUiState.Error -> weatherUiState.cached
        else -> null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp + bottomInset)) {
            // 地名 (明朝) と天気を一段にまとめる
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    // 地名がまだ無いとき (「現在地を取得中…」) は短くせずそのまま出す
                    text = if (locationName.isEmpty() || locationName.startsWith("現在地")) {
                        locationName.ifEmpty { "現在地" }
                    } else {
                        com.gorite.cyclemap.ui.cycling.extractShortLocalityName(locationName)
                    },
                    fontFamily = pal.displayFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onRefreshWeather),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val category = weather?.let { WeatherCategory.fromCode(it.weatherCode) }
                        Text(
                            text = weather?.currentTemp?.let { "%.0f°".format(Locale.US, it) } ?: "天気 --",
                            fontSize = 13.sp,
                            color = if (category != null) weatherTint(category, sunny = pal.warning) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        weather?.currentPop?.let { pop ->
                            Text("雨$pop%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 探す (親指の位置)
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .clickable(onClick = onOpenSearch),
            ) {
                Row(modifier = Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_lucide_search),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("どこへ行く？", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))

            // お気に入りがあるときは従来どおり先頭の二件までを表示する
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                favorites.take(2).forEach { fav ->
                    Shortcut(
                        title = fav.name,
                        icon = R.drawable.ic_google_bookmark,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenFavorite(fav) },
                    )
                }
                if (favorites.isEmpty()) {
                    Shortcut("保存済み", R.drawable.ic_google_bookmark, Modifier.weight(1f), onClick = onOpenSaved)
                }
                Shortcut("コンビニ", R.drawable.ic_lucide_store, Modifier.weight(1f), onClick = { onQuickSpot(QuickSpotType.CONVENIENCE) })
                if (favorites.size < 2) {
                    Shortcut("トイレ", R.drawable.ic_lucide_toilet, Modifier.weight(1f), onClick = { onQuickSpot(QuickSpotType.RESTROOM) })
                }
            }

            Spacer(Modifier.height(10.dp))

            // 走る (画面でいちばん大事な操作なので朱で塗る)。記録中は止めるボタンになる
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isRecording) pal.dangerContainer else pal.primaryAction,
                border = if (isRecording) BorderStroke(1.dp, pal.danger) else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onToggleRecording),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRecording) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(pal.danger))
                            Spacer(Modifier.width(10.dp))
                            Text("記録を止める (${gpxPointCount}点)", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = pal.danger)
                        }
                    } else {
                        Text(
                            "ルートなしで走る",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = pal.onPrimaryAction,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "今日 ${formatKm(totals.todayMeters)} km ・ 今週 ${formatKm(totals.weekMeters)} km　記録を見る ›",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRecords),
            )
        }
    }
}

@Composable
private fun Shortcut(title: String, icon: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(title, modifier = Modifier.weight(1f, fill = false), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun formatKm(meters: Double): String = "%.1f".format(Locale.US, meters / 1000.0)
