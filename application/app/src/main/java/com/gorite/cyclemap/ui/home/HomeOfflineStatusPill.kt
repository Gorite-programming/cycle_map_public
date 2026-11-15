package com.gorite.cyclemap.ui.home

import android.location.Location
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.data.RoutingGraphSelection
import com.gorite.cyclemap.data.RoutingGraphSelector
import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.utils.cycleMapDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal enum class HomeOfflineTone {
    READY,
    WARNING,
    NEUTRAL,
}

internal data class HomeOfflineStatus(
    val label: String,
    val tone: HomeOfflineTone,
)

/** 都道府県名を表示用に短縮する (例: 「広島県」→「広島」、「東京都」→「東京」、「北海道」→「北海道」)。 */
internal fun shortPrefectureName(prefectureName: String): String =
    when {
        prefectureName == "北海道" -> prefectureName
        prefectureName.endsWith("県") || prefectureName.endsWith("府") || prefectureName.endsWith("都") ->
            prefectureName.dropLast(1)
        else -> prefectureName
    }

/** 現在地の座標から県別グラフの選択結果を求める。未取得・不正値なら null。 */
internal fun resolvePrefectureAtLocation(
    latitude: Double?,
    longitude: Double?,
): RoutingGraphSelection? {
    if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) {
        return null
    }
    return RoutingGraphSelector.select(latitude, longitude)
}

/** 読み込み済みのグラフ名が、現在地の県グラフと一致するかを大文字小文字を無視して判定する。 */
internal fun isLoadedGraphForSelection(
    selection: RoutingGraphSelection.Available,
    loadedGraphName: String?,
): Boolean = loadedGraphName != null && loadedGraphName.equals(selection.fileName, ignoreCase = true)

/** 現在地の県判定とルートデータの有無から、ホーム上部の状態ピル表示を組み立てる。 */
internal fun buildHomeOfflineStatus(
    selection: RoutingGraphSelection?,
    hasGraphOnDevice: Boolean?,
    loadedGraphName: String?,
): HomeOfflineStatus {
    return when (selection) {
        null -> HomeOfflineStatus(
            label = "現在地を取得中",
            tone = HomeOfflineTone.NEUTRAL,
        )
        is RoutingGraphSelection.Unavailable -> {
            val shortName = selection.prefectureName?.let(::shortPrefectureName)
            val label = if (shortName != null) "$shortName ・ ルートデータなし" else "ルートデータなし"
            HomeOfflineStatus(label = label, tone = HomeOfflineTone.WARNING)
        }
        is RoutingGraphSelection.Available -> {
            val shortName = shortPrefectureName(selection.prefectureName)
            val isReady = isLoadedGraphForSelection(selection, loadedGraphName) || hasGraphOnDevice == true
            when {
                isReady -> HomeOfflineStatus(
                    label = "$shortName ・ オフラインで使えます",
                    tone = HomeOfflineTone.READY,
                )
                hasGraphOnDevice == null -> HomeOfflineStatus(
                    label = "$shortName ・ 確認中",
                    tone = HomeOfflineTone.NEUTRAL,
                )
                else -> HomeOfflineStatus(
                    label = "$shortName ・ ルートデータなし",
                    tone = HomeOfflineTone.WARNING,
                )
            }
        }
    }
}

/** ホーム画面の地図上部に出す、現在地の県とオフラインデータの状態ピル。 */
@Composable
internal fun HomeOfflineStatusPill(
    currentLocation: Location?,
    loadedGraphName: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checkGraphFiles: (File, String) -> Boolean = { dir, fileName ->
        RoutingGraphSelector.resolveGraphFiles(dir, fileName) != null
    },
) {
    val context = LocalContext.current
    val lat = currentLocation?.latitude
    val lon = currentLocation?.longitude
    val selection = remember(lat, lon) {
        resolvePrefectureAtLocation(lat, lon)
    }
    val availableSelection = selection as? RoutingGraphSelection.Available
    val targetPrefId = availableSelection?.prefectureId
    val targetFileName = availableSelection?.fileName

    // ファイル有無の確認は I/O のため、現在地の県が変わったときだけ Dispatchers.IO で実行する
    var checkedGraph by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(targetPrefId, targetFileName) {
        if (targetPrefId == null || targetFileName == null) {
            checkedGraph = null
        } else {
            val exists = withContext(Dispatchers.IO) {
                checkGraphFiles(cycleMapDataDir(context), targetFileName)
            }
            checkedGraph = targetPrefId to exists
        }
    }
    val hasGraphOnDevice = if (targetPrefId != null && checkedGraph?.first == targetPrefId) {
        checkedGraph?.second
    } else {
        null
    }

    val status = remember(selection, hasGraphOnDevice, loadedGraphName) {
        buildHomeOfflineStatus(
            selection = selection,
            hasGraphOnDevice = hasGraphOnDevice,
            loadedGraphName = loadedGraphName,
        )
    }
    val dotColor = when (status.tone) {
        HomeOfflineTone.READY -> CycleMapTheme.palette.success
        HomeOfflineTone.WARNING -> CycleMapTheme.palette.warning
        HomeOfflineTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = status.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
