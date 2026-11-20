package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MapDrawerContent(
    selectedMenu: String,
    onClose: () -> Unit,
    onSearchClick: () -> Unit,
    onSavedClick: () -> Unit,
    onRecordHistoryClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onImportGpxClick: () -> Unit = {},
    on3dSimulationClick: () -> Unit = {},
    onLicenseClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeveloperOptionsClick: () -> Unit,
    onVersionClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // メニューは画面の上端から描かれるので、ステータスバーの分を空けて時計と重ならないようにする
                .statusBarsPadding()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "CycleMap",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    BuildConfig.VERSION_NAME,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_x),
                    contentDescription = "メニューを閉じる",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
        Column(modifier = Modifier.weight(1f).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            DrawerSectionHeader("走る")
            DrawerMenuItem(
                label = "目的地検索",
                iconRes = R.drawable.ic_lucide_search,
                selected = selectedMenu == "検索",
                onClick = onSearchClick,
            )
            DrawerMenuItem(
                label = "保存済み",
                // ic_lucide_* に星/ブックマークが存在しないため既存の ic_google_bookmark を使用
                iconRes = R.drawable.ic_google_bookmark,
                selected = selectedMenu == "保存済み",
                onClick = onSavedClick,
            )
            DrawerMenuItem(
                label = "走行記録",
                iconRes = R.drawable.ic_lucide_history,
                selected = selectedMenu == "記録",
                onClick = onRecordHistoryClick,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            DrawerSectionHeader("地図とデータ")
            DrawerMenuItem(
                label = "オフラインデータ",
                iconRes = R.drawable.ic_lucide_download,
                selected = selectedMenu == "ダウンロード",
                onClick = onDownloadClick,
            )
            DrawerMenuItem(
                label = "GPXを読み込む",
                iconRes = R.drawable.ic_lucide_route,
                selected = selectedMenu == "GPX",
                onClick = onImportGpxClick,
            )
            DrawerMenuItem(
                label = "3D地形ビュー",
                iconRes = R.drawable.ic_lucide_mountain,
                selected = selectedMenu == "3D",
                onClick = on3dSimulationClick,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            DrawerSectionHeader("アプリ")
            DrawerMenuItem(
                label = "設定",
                iconRes = R.drawable.ic_lucide_settings,
                selected = selectedMenu == "設定",
                onClick = onSettingsClick,
            )
            DrawerMenuItem(
                label = "ライセンス",
                iconRes = R.drawable.ic_lucide_scale,
                selected = selectedMenu == "ライセンス",
                onClick = onLicenseClick,
            )
            if (BuildConfig.DEBUG) {
                DrawerMenuItem(
                    label = "開発者オプション",
                    iconRes = R.drawable.ic_lucide_wrench,
                    selected = selectedMenu == "開発者",
                    onClick = onDeveloperOptionsClick,
                )
            }
            DrawerMenuItem(
                label = "バージョン情報",
                iconRes = R.drawable.ic_lucide_info,
                selected = selectedMenu == "バージョン情報",
                onClick = onVersionClick,
            )

            Text(
                // 出典ごとに改行し、語の途中で折り返さないようにする
                text = "地図データ © OpenStreetMap contributors\n国土地理院",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerMenuItem(
    label: String,
    @DrawableRes iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        },
        selected = selected,
        onClick = onClick,
    )
}
