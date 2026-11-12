package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.R
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.data.DownloadStatusManager
import com.gorite.cyclemap.data.MapSourceType
import com.gorite.cyclemap.data.Prefecture
import com.gorite.cyclemap.data.PrefectureDataStatus
import com.gorite.cyclemap.data.offlineDataStatus
import com.gorite.cyclemap.utils.cycleMapDataDir
import com.gorite.cyclemap.data.PrefectureData
import com.gorite.cyclemap.data.TileDownloader
import com.gorite.cyclemap.data.TileProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.util.Locale

@Composable
internal fun PrefectureListDialog(
    context: Context,
    onDismiss: () -> Unit,
    onPrefectureSelected: (Prefecture) -> Unit,
) {
    var selectedRegionIndex by remember { mutableIntStateOf(5) } // デフォルト中国地方
    var downloadStatus by remember { mutableStateOf<Map<String, Pair<Boolean, Boolean>>>(emptyMap()) }

    var dataStatus by remember { mutableStateOf<List<PrefectureDataStatus>>(emptyList()) }

    LaunchedEffect(context) {
        dataStatus = withContext(Dispatchers.IO) { offlineDataStatus(cycleMapDataDir(context)) }
        downloadStatus = withContext(Dispatchers.IO) {
            PrefectureData.ALL.associate { pref ->
                pref.id to (
                    DownloadStatusManager.isDownloaded(context, pref.id, MapSourceType.GSI) to
                        DownloadStatusManager.isDownloaded(context, pref.id, MapSourceType.OSM)
                    )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("オフラインデータ") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().height(520.dp)) {
                OfflineDataStatusSection(dataStatus)
                Spacer(modifier = Modifier.height(10.dp))
                Text("地図タイルの保存 (地理院/OSM)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                // Region Tab
                ScrollableTabRow(
                    selectedTabIndex = selectedRegionIndex,
                    edgePadding = 0.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PrefectureData.REGIONS.forEachIndexed { index, regionName ->
                        Tab(
                            selected = selectedRegionIndex == index,
                            onClick = { selectedRegionIndex = index },
                            text = { Text(regionName, fontSize = 12.sp) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // ここで保存するのは表示用の地図タイル (地理院/OSM) だけ。経路探索・検索のデータは別で、現在は中国 5 県のみ対応。
                Text(
                    "県をタップすると、その県の地図タイルを保存できます (ルート探索・検索データは含みません)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Prefecture List in selected region
                val currentRegion = PrefectureData.REGIONS[selectedRegionIndex]
                val prefectures = remember(currentRegion) {
                    PrefectureData.ALL.filter { it.region == currentRegion }
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(prefectures) { pref ->
                        val status = downloadStatus[pref.id]
                        val isGsi = status?.first == true
                        val isOsm = status?.second == true
                        val tileCount = PrefectureData.calculateTileCount(pref.bounds)
                        val sizeMb = PrefectureData.estimateSizeMb(tileCount)

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onPrefectureSelected(pref) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(pref.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("約 ${tileCount}枚 (%.1f MB)".format(Locale.US, sizeMb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (isGsi) {
                                        Text(
                                            "地理院済",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                    if (isOsm) {
                                        Text(
                                            "OSM済",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

internal fun startPrefectureDownload(
    context: Context,
    pref: Prefecture,
    sourceType: MapSourceType,
    source: XYTileSource,
    onProgress: (TileProgress) -> Unit,
    onFinished: () -> Unit,
) {
    Thread {
        try {
            TileDownloader.download(context, pref, sourceType, source, onProgress)
        } finally {
            onFinished()
        }
    }.start()
}

@Composable
private fun OfflineDataStatusSection(status: List<PrefectureDataStatus>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ルート探索・施設検索・地図データ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                "対応は中国5県です。このデータはアプリ内では取得できず、PCで作成して端末へ転送します。入っていない県では、ルート探索と施設検索が使えません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.forEach { st ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(st.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        when {
                            st.isComplete -> "✓ そろっています"
                            st.isEmpty -> "未導入"
                            else -> buildList {
                                if (!st.hasRouting) add("ルート")
                                if (!st.hasSearch) add("検索")
                                if (!st.hasVectorMap) add("地図")
                            }.joinToString("・") + "が不足"
                        },
                        fontSize = 13.sp,
                        color = if (st.isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
