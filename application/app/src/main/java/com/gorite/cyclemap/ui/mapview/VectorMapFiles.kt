package com.gorite.cyclemap.ui.mapview

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import android.content.Context
import com.gorite.cyclemap.utils.cycleMapDataDir
import com.gorite.cyclemap.data.OfflinePackageManager
import com.gorite.cyclemap.data.SearchDbSelection
import com.gorite.cyclemap.data.SearchDbSelector
import com.gorite.cyclemap.data.TileSourceSelection
import com.gorite.cyclemap.data.TileSourceSelector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 端末内の PMTiles ファイルを探すヘルパー関数。
 * 現在地の県に対応するファイルだけを返し、未導入時に他県のタイルへフォールバックしない。
 */
fun resolveOfflinePmtilesPath(context: Context, lat: Double, lon: Double): String? {
    val dataDir = cycleMapDataDir(context)
    return TileSourceSelector.resolveTileForLocation(dataDir, lat, lon)?.absolutePath
}

/**
 * 端末内の検索DBファイル (.search.db) を探すヘルパー関数。
 * 指定座標に対応する県DBを優先し、フォールバックとして Hiroshima.search.db や配置済みDBを探索する。
 */
fun resolveSearchDbFile(context: Context, lat: Double, lon: Double): File? {
    val dataDir = cycleMapDataDir(context)
    val sel = SearchDbSelector.select(lat, lon)
    if (sel is SearchDbSelection.Available) {
        val f = SearchDbSelector.resolveDbFile(dataDir, sel.fileName)
        if (f != null && f.exists() && f.length() > 0) return f
    }
    val candidates = listOf(
        SearchDbSelector.findFirstAvailableDb(dataDir),
        File(dataDir, "Hiroshima.search.db"),
        File(context.getExternalFilesDir("Documents/CycleMap"), "Hiroshima.search.db"),
        File("/sdcard/Android/data/com.gorite.cyclemap/files/Documents/CycleMap/Hiroshima.search.db"),
        File("/storage/emulated/0/Android/data/com.gorite.cyclemap/files/Documents/CycleMap/Hiroshima.search.db"),
        File("/sdcard/Android/data/com.gorite.cyclemap.test/files/Documents/CycleMap/Hiroshima.search.db"),
        File("/storage/emulated/0/Android/data/com.gorite.cyclemap.test/files/Documents/CycleMap/Hiroshima.search.db"),
    )
    for (candidate in candidates) {
        if (candidate != null && candidate.exists() && candidate.length() > 0) {
            return candidate
        }
    }
    return null
}

/**
 * オフラインパッケージファイルの検証を行うヘルパー関数。
 */
fun verifyOfflineDataPackage(file: File, expectedSha256: String? = null): Boolean {
    if (!file.exists() || file.length() == 0L) return false
    return if (!expectedSha256.isNullOrBlank()) {
        OfflinePackageManager.verifyPackageIntegrity(file, expectedSha256)
    } else {
        true
    }
}

@Composable
internal fun OfflineVectorMissingBanner(
    prefectureName: String?,
    pmtilesFileName: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val guide = remember(prefectureName, pmtilesFileName) {
        buildOfflineDataGuide(prefectureName, pmtilesFileName, cycleMapDataDir(context).absolutePath)
    }
    var showSteps by remember { mutableStateOf(false) }
    val dark = CycleMapTheme.palette.isDark

    Card(
        colors = CardDefaults.cardColors(containerColor = if (dark) com.gorite.cyclemap.ui.theme.SumiShu.Sumi3 else Color(0xFF37474F)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 152.dp, start = 16.dp, end = 88.dp), // 右端の地図ボタンと重ならないよう広めに空ける
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = guide.title,
                color = if (dark) com.gorite.cyclemap.ui.theme.SumiShu.Washi else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = guide.summary,
                color = if (dark) com.gorite.cyclemap.ui.theme.SumiShu.Usuzumi else Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
            )
            if (guide.hasInstallSteps) {
                TextButton(onClick = { showSteps = true }) {
                    Text("導入方法を見る", color = if (dark) com.gorite.cyclemap.ui.theme.SumiShu.Shu else Color(0xFF90CAF9))
                }
            }
        }
    }

    if (showSteps) {
        AlertDialog(
            onDismissRequest = { showSteps = false },
            confirmButton = { TextButton(onClick = { showSteps = false }) { Text("閉じる") } },
            title = { Text("データの入れ方") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    guide.steps.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("必要なファイル", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    guide.requiredFiles.forEach { Text("・$it", fontSize = 13.sp) }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("入れるフォルダ", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    SelectionContainer { Text(guide.folderPath, fontSize = 12.sp) }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(OFFLINE_DATA_FOLDER_NOTE, fontSize = 12.sp)
                }
            },
        )
    }
}
