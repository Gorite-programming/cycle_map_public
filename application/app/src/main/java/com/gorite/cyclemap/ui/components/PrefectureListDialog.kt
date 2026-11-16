package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.data.formatOfflineDataSize
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
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.TabRow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import com.gorite.cyclemap.data.OfflineDataImporter
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.data.DownloadStatusManager
import com.gorite.cyclemap.data.MapSourceType
import com.gorite.cyclemap.data.Prefecture
import com.gorite.cyclemap.data.OfflineDataDeleter
import com.gorite.cyclemap.ui.theme.CycleMapColors
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
    offlineDataUsage: () -> OfflineDataDeleter.Usage = { OfflineDataDeleter.Usage(busy = true) },
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var dataLoaded by remember { mutableStateOf(false) }
    var selectedRegionIndex by remember { mutableIntStateOf(5) } // デフォルト中国地方
    var downloadStatus by remember { mutableStateOf<Map<String, Pair<Boolean, Boolean>>>(emptyMap()) }

    var dataStatus by remember { mutableStateOf<List<PrefectureDataStatus>>(emptyList()) }
    var deletionPlan by remember { mutableStateOf<OfflineDataDeleter.Plan?>(null) }
    var deletionMessage by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val latestUsage by rememberUpdatedState(offlineDataUsage)

    // 端末に保存したファイル (県の ZIP、または .graph などの単体) の取り込み
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importedAny by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        importMessage = "取り込み中…"
        scope.launch {
            importMessage = try {
                val result = withContext(Dispatchers.IO) {
                    OfflineDataImporter.import(context, uris, cycleMapDataDir(context)) { name ->
                        scope.launch { importMessage = "取り込み中: $name" }
                    }
                }
                dataStatus = withContext(Dispatchers.IO) { offlineDataStatus(cycleMapDataDir(context)) }
                importedAny = importedAny || result.imported.isNotEmpty()
                if (result.imported.isEmpty()) {
                    "取り込めるファイルがありませんでした (県の ZIP か .graph / .search.db / .pmtiles を選んでください)"
                } else {
                    "取り込みました: " + result.imported.joinToString("、") + "\n再起動すると反映されます"
                }
            } catch (e: Exception) {
                "取り込みに失敗しました: ${e.message}"
            }
            importing = false
        }
    }

    LaunchedEffect(context) {
        dataStatus = withContext(Dispatchers.IO) { offlineDataStatus(cycleMapDataDir(context)) }
        dataLoaded = true
        downloadStatus = withContext(Dispatchers.IO) {
            PrefectureData.ALL.associate { pref ->
                pref.id to (
                    DownloadStatusManager.isDownloaded(context, pref.id, MapSourceType.GSI) to
                        DownloadStatusManager.isDownloaded(context, pref.id, MapSourceType.OSM)
                    )
            }
        }
    }

    val currentUsage = offlineDataUsage()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(16.dp)) {
                Text("オフラインデータ", style = MaterialTheme.typography.titleLarge)
                TabRow(selectedTabIndex = selectedTab) {
                    listOf("端末のデータ", "地図タイルの保存").forEachIndexed { index, label ->
                        Tab(selected = selectedTab == index, onClick = { selectedTab = index },
                            text = { Text(label, style = MaterialTheme.typography.labelLarge) })
                    }
                }
                if (selectedTab == 0) {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Text(
                                if (dataLoaded) "${dataStatus.size} 県 ・ 合計 ${formatOfflineDataSize(dataStatus.sumOf { it.totalBytes })}"
                                else "端末のデータを確認中…",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(enabled = !importing && !deleting && deletionPlan == null, onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                                        Text("ファイルから取り込む")
                                    }
                                    if (importedAny && !importing) {
                                        OutlinedButton(onClick = { restartApp(context) }) { Text("再起動", maxLines = 1, softWrap = false) }
                                    }
                                }
                                importMessage?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("県の ZIP またはデータファイルを取り込めます。PC からの転送にも対応しています。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            deletionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        items(dataStatus, key = { it.id }) { status ->
                            val prefecture = PrefectureData.ALL.first { it.id == status.id }
                            val refusal = OfflineDataDeleter.refusalReason(prefecture, currentUsage)
                            OfflineDataStatusRow(status, refusal, !importing && !deleting) {
                                deleting = true
                                scope.launch {
                                    try {
                                        val plan = withContext(Dispatchers.IO) {
                                            OfflineDataDeleter.plan(cycleMapDataDir(context), prefecture)
                                        }
                                        val reason = OfflineDataDeleter.refusalReason(plan, latestUsage())
                                        if (reason != null) deletionMessage = reason
                                        else if (plan.files.isEmpty()) deletionMessage = "削除できる県別ファイルはありません。tiles/ 内のデータは保持します。"
                                        else deletionPlan = plan
                                    } finally { deleting = false }
                                }
                            }
                        }
                        if (dataLoaded) {
                            if (dataStatus.isEmpty()) {
                                item { Text("端末にデータが入っている県はまだありません", style = MaterialTheme.typography.bodySmall) }
                            }
                            item { EmptyPrefecturesSection(dataStatus) }
                        }
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
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
                        // ここで保存するのは表示用の地図タイル (地理院/OSM) だけ。経路探索・検索のデータは別途配置する。
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(pref.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Text("${tileCount}枚 ・ 約 %.1f MB (概算)".format(Locale.US, sizeMb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("閉じる") }
            }
        }
    }
    deletionPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { deletionPlan = null },
            title = { Text("${plan.prefecture.name}のデータを削除") },
            text = {
                Text("${plan.kinds.joinToString("・")}\n${plan.files.size} ファイル・合計 ${formatOfflineDataSize(plan.totalBytes)}\n\n元に戻せません。\ntiles/ 内のデータは保持します。")
            },
            confirmButton = {
                TextButton(onClick = {
                    // 再判定と削除の間で画面のグラフ切替が走らないよう、同じメインスレッドで実行する。
                    val result = OfflineDataDeleter.delete(plan, latestUsage())
                    deletionPlan = null
                    deletionMessage = when {
                        result.refusalReason != null -> result.refusalReason
                        result.failed.isNotEmpty() -> "削除できなかったファイル: ${result.failed.joinToString("、") { it.name }}"
                        else -> "${plan.prefecture.name}のデータを削除しました。"
                    }
                    deleting = true
                    scope.launch {
                        try {
                            dataStatus = withContext(Dispatchers.IO) { offlineDataStatus(cycleMapDataDir(context)) }
                        } finally { deleting = false }
                    }
                }) { Text("削除する", color = CycleMapColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deletionPlan = null }) { Text("キャンセル") } },
        )
    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfflineDataStatusRow(
    status: PrefectureDataStatus,
    refusalReason: String?,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(status.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(formatOfflineDataSize(status.totalBytes), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = canDelete && refusalReason == null, onClick = onDelete) {
                    Text(if (refusalReason != null) "使用中" else "削除",
                        color = if (canDelete && refusalReason == null) CycleMapColors.Danger
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (refusalReason != null) {
                Text(refusalReason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("ルート" to status.hasRouting, "検索" to status.hasSearch,
                    "地図" to status.hasVectorMap, "地形" to status.hasTerrain,
                    "高速探索" to status.hasFastRouting).forEach { (label, present) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (present) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.background(
                            if (present) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)
                        ).padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
            if (status.isComplete) {
                Text("ルート・検索・地図が入っています", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EmptyPrefecturesSection(status: List<PrefectureDataStatus>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val installedIds = status.map { it.id }.toSet()
    val empty = PrefectureData.ALL.filterNot { it.id in installedIds }
    if (empty.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text("データの無い県 (${empty.size})  " + if (expanded) "▴" else "▾")
        }
        if (expanded) {
            PrefectureData.REGIONS.forEach { region ->
                val names = empty.filter { it.region == region }.map { it.name }
                if (names.isNotEmpty()) {
                    Text(region, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(names.joinToString(" ・ "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 取り込んだデータを読み直すため、アプリを起動し直す (経路・検索・地図のファイルは起動時に選ぶ)。 */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
