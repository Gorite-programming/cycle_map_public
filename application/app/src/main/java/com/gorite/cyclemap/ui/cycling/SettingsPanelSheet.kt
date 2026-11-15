package com.gorite.cyclemap.ui.cycling

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.BuildConfig
import com.gorite.cyclemap.R
import com.gorite.cyclemap.power.BatteryStatus
import com.gorite.cyclemap.speech.VoiceGuidanceMode
import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.ui.theme.LocalAnimationEnabled
import com.gorite.cyclemap.ui.theme.Motion
import com.gorite.cyclemap.ui.theme.ThemeMode

// ---------------------------------------------------------------------------
// 設定パネル
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanelSheet(
    autoReroute: Boolean,
    onAutoReroute: (Boolean) -> Unit,
    headingUp: Boolean,
    onHeadingUp: (Boolean) -> Unit,
    layerIndex: Int,
    layerLabels: List<String>,
    onLayerSelect: (Int) -> Unit,
    poiVisible: Boolean,
    onPoiVisible: (Boolean) -> Unit,
    poiSelected: Set<PoiCategory>,
    onPoiToggle: (PoiCategory) -> Unit,
    onPoiSetSelection: (Set<PoiCategory>) -> Unit = {},
    voiceGuidanceMode: VoiceGuidanceMode = VoiceGuidanceMode.VOICEVOX,
    onVoiceGuidanceModeChange: (VoiceGuidanceMode) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.AUTO,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    batteryStatus: BatteryStatus? = null,
    animationEnabled: Boolean = true,
    onAnimationEnabledChange: (Boolean) -> Unit = {},
    hasLocationPermission: Boolean,
    onManageData: () -> Unit,
    onImportGpx: () -> Unit = {},
    onLicense: () -> Unit,
    onVersion: () -> Unit,
    onDeveloperOptions: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // ステータスバーより下で止める (端末がダーク設定でアプリがライトのとき、白い時計が明るいシートに溶けるため)
    ModalBottomSheet(modifier = Modifier.statusBarsPadding(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("設定", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            SettingDescription("変更はすぐ保存されます")
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("端末の状態", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "位置情報: ${if (hasLocationPermission) "許可済み" else "未許可 — 現在地と案内が使えません"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasLocationPermission) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                    if (batteryStatus != null) {
                        val statusText = buildString {
                            append("バッテリー: ${batteryStatus.levelPercent}%")
                            if (batteryStatus.isCharging) append(" (充電中)")
                            if (batteryStatus.isPowerSaveMode) append(" · OS省電力モード")
                            if (batteryStatus.isLowPower) append(" · 省電力(3D→2D制限中)")
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (batteryStatus.isLowPower) CycleMapTheme.palette.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text("ナビの動作", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SettingSwitch("ルートを外れたら自動で探し直す", autoReroute, onAutoReroute,
                subtitle = "コースを外れたとき、現在地から経路を作り直します")
            SettingSwitch("進行方向を上にする", headingUp, onHeadingUp,
                subtitle = "走っている向きに合わせて地図を回します。オフのときは北が上です")

            Spacer(modifier = Modifier.height(4.dp))
            Text("音声案内", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            SettingDescription("曲がる場所やルートを外れたことを声で知らせます")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceGuidanceMode.entries.forEach { mode ->
                    val isSelected = mode == voiceGuidanceMode
                    val animEnabled = LocalAnimationEnabled.current
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        animationSpec = Motion.motionTween(Motion.DURATION_SHORT, enabled = animEnabled),
                        label = "VoiceModeBg",
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = Motion.motionTween(Motion.DURATION_SHORT, enabled = animEnabled),
                        label = "VoiceModeContent",
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = bgColor,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onVoiceGuidanceModeChange(mode) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                when (mode) {
                                    VoiceGuidanceMode.VOICEVOX -> "音声あり"
                                    VoiceGuidanceMode.OFF -> "音声なし"
                                },
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = contentColor,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("地図の表示", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("地図の種類", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            layerLabels.forEachIndexed { index, label ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onLayerSelect(index) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = layerIndex == index, onClick = { onLayerSelect(index) })
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(label)
                        val description = when {
                            "ベクター" in label -> "標準。データを入れた県は通信なしで見られます"
                            "地理院標準" in label || "自転車向け" in label -> "通信するか、事前に保存した範囲で見られます"
                            "地形起伏" in label -> "通信が必要です"
                            else -> null
                        }
                        description?.let { SettingDescription(it) }
                    }
                }
            }
            SettingSwitch("施設アイコンを表示", poiVisible, onPoiVisible,
                subtitle = "拡大の度合いに応じて、駅やコンビニなどを地図に出します")
            if (poiVisible) {
                var poiExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { poiExpanded = !poiExpanded }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "表示カテゴリ: ${poiSelected.size}/${PoiCategory.entries.size} 件",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (poiExpanded) "閉じる" else "変更",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // グループ分けと「標準に戻す / すべて選択 / すべて解除」付きの選び方 (PoiCategoryPicker.kt)
                if (poiExpanded) PoiCategoryPicker(
                    selected = poiSelected,
                    onToggle = onPoiToggle,
                    onSetSelection = onPoiSetSelection,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("見た目", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            Spacer(modifier = Modifier.height(2.dp))
            Text("テーマ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            SettingDescription("自動は、端末がダーク設定ならダーク、それ以外は日の出・日の入りで切り替えます")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeMode.entries.forEach { mode ->
                    val isSelected = mode == themeMode
                    val animEnabled = LocalAnimationEnabled.current
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        animationSpec = Motion.motionTween(Motion.DURATION_SHORT, enabled = animEnabled),
                        label = "ThemeModeBg",
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = Motion.motionTween(Motion.DURATION_SHORT, enabled = animEnabled),
                        label = "ThemeModeContent",
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = bgColor,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onThemeModeChange(mode) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                when (mode) {
                                    ThemeMode.AUTO -> "自動"
                                    ThemeMode.LIGHT -> "ライト"
                                    ThemeMode.DARK -> "ダーク"
                                },
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = contentColor,
                            )
                        }
                    }
                }
            }

            SettingSwitch("動きの演出", animationEnabled, onAnimationEnabledChange,
                subtitle = "画面の切り替えや地図の移動をなめらかに見せます。オフにすると電池の減りを少し抑えます")

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("データ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SettingActionRow(
                label = "オフラインデータ管理",
                subtitle = "地図・検索・経路のデータを追加・管理します",
                iconRes = R.drawable.ic_lucide_download,
                onClick = onManageData,
            )
            SettingActionRow(
                label = "GPXを読み込む",
                subtitle = "GPXファイルからコースを取り込みます",
                iconRes = R.drawable.ic_lucide_route,
                onClick = onImportGpx,
            )
            var offlineExpanded by remember { mutableStateOf(false) }
            SettingActionRow(
                label = "オフラインで使える機能",
                iconRes = R.drawable.ic_lucide_cloud_off,
                trailing = if (offlineExpanded) "閉じる ∧" else "開く ›",
                onClick = { offlineExpanded = !offlineExpanded },
            )
            if (offlineExpanded) {
                OFFLINE_CAPABILITY_NOTES.forEach { line -> SettingDescription("・$line") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("アプリ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SettingActionRow("ライセンス", R.drawable.ic_lucide_scale, onLicense)
            SettingActionRow(
                label = "バージョン情報",
                iconRes = R.drawable.ic_lucide_info,
                trailing = "${BuildConfig.VERSION_NAME}  ›",
                onClick = onVersion,
            )
            if (BuildConfig.DEBUG) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("開発用情報", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    "ビルド元コミット: ${BuildConfig.GIT_HASH}\nビルド日時: ${BuildConfig.BUILD_TIME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onDeveloperOptions != null) {
                    TextButton(onClick = onDeveloperOptions) { Text("開発者オプション") }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { SettingDescription(it) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingActionRow(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: String = "›",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null,
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { SettingDescription(it) }
        }
        Text(trailing, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
