package com.gorite.cyclemap.ui.cycling

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.R
import com.gorite.cyclemap.data.FavoriteSpot
import com.gorite.cyclemap.data.FavoritesManager
import com.gorite.cyclemap.utils.haversineMeters

// ---------------------------------------------------------------------------
// スポットパネル (サイクリスト向けPOIクイック検索・オフラインDB)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotPanelSheet(
    selectedCategory: SpotQuickCategory?,
    onCategorySelect: (SpotQuickCategory) -> Unit,
    isLoading: Boolean,
    spots: List<NearbySpot>,
    message: String?,
    onSpotClick: (NearbySpot) -> Unit,
    onDismiss: () -> Unit,
    onQuickSpotTypeSelect: ((QuickSpotType) -> Unit)? = null,
    onRouteToSpot: ((NearbySpot) -> Unit)? = null,
    initialShowFavoritesOnly: Boolean = false,
    origin: Pair<Double, Double>? = null,
) {
    val context = LocalContext.current
    val favoritesManager = remember(context) { FavoritesManager.getInstance(context) }
    val favorites by favoritesManager.favorites.collectAsState()
    var showFavoritesOnly by remember(initialShowFavoritesOnly) { mutableStateOf(initialShowFavoritesOnly) }
    var showSaveCurrentDialog by remember { mutableStateOf(false) }
    if (showSaveCurrentDialog && origin != null) {
        com.gorite.cyclemap.ui.components.SaveFavoriteDialog(
            title = "現在地をお気に入りに追加",
            initialName = "自宅",
            onSave = { name ->
                favoritesManager.addFavorite(com.gorite.cyclemap.data.customFavorite(name, origin.first, origin.second))
                showSaveCurrentDialog = false
            },
            onDismiss = { showSaveCurrentDialog = false },
        )
    }

    // ステータスバーより下で止める (端末がダーク設定でアプリがライトのとき、白い時計が明るいシートに溶けるため)
    ModalBottomSheet(modifier = Modifier.statusBarsPadding(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 内容が長いときもタブバーを押し出さないよう、残りの高さの中でスクロールさせる
                .weight(1f, fill = false)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showFavoritesOnly) "お気に入りスポット" else "周辺スポット",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                SheetCloseButton(onClick = onDismiss)
            }
            Text(
                if (showFavoritesOnly) "登録したお気に入りの場所一覧 (タップで目的地に設定)"
                else "トイレ・コンビニ・休憩場所をオフラインDBから検索",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!showFavoritesOnly) {
                if (onQuickSpotTypeSelect != null) {
                    Text(
                        "クイック補給・緊急スポット (ワンタップ検索)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    QuickSpotFilterRow(
                        selectedType = null,
                        onSelectType = { type ->
                            onQuickSpotTypeSelect(type)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "一般カテゴリ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SpotQuickCategory.entries.chunked(3).forEach { rowCats ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowCats.forEach { cat ->
                            SpotChip(cat, selectedCategory == cat, { onCategorySelect(cat) }, Modifier.weight(1f))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            HorizontalDivider()
            if (showFavoritesOnly) {
                if (origin != null) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { showSaveCurrentDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("現在地をお気に入りに追加 (自宅など)") }
                }
                if (favorites.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "お気に入りはまだ登録されていません。\nスポット一覧の★、ルート確認の★、上のボタンから登録できます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(favorites, key = { it.id }) { fav ->
                            val favDistance = origin?.let {
                                haversineMeters(it.first, it.second, fav.latitude, fav.longitude)
                            }
                            val favAsSpot = NearbySpot(
                                name = fav.name,
                                category = fav.category,
                                latitude = fav.latitude,
                                longitude = fav.longitude,
                                distanceM = favDistance ?: 0.0,
                                address = fav.address,
                            )
                            QuickSpotCard(
                                spot = favAsSpot,
                                isFavorite = true,
                                onToggleFavorite = { favoritesManager.removeFavorite(fav.id) },
                                onSpotClick = { onSpotClick(favAsSpot) },
                                onRouteClick = onRouteToSpot?.let { handler -> { handler(favAsSpot) } },
                                origin = origin,
                                distanceMeters = favDistance,
                            )
                        }
                    }
                }
            } else {
                when {
                    isLoading -> Text("検索中…", style = MaterialTheme.typography.bodyMedium)
                    message != null -> Text(message, style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(spots, key = { "${it.name}:${it.latitude}:${it.longitude}" }) { spot ->
                            val isFav = favoritesManager.isFavorite(spot.latitude, spot.longitude)
                            QuickSpotCard(
                                spot = spot,
                                isFavorite = isFav,
                                onToggleFavorite = {
                                    favoritesManager.toggleFavorite(
                                        FavoriteSpot(
                                            id = "${spot.name}_${spot.latitude}_${spot.longitude}",
                                            name = spot.name,
                                            category = spot.category,
                                            latitude = spot.latitude,
                                            longitude = spot.longitude,
                                            address = spot.address,
                                        ),
                                    )
                                },
                                onSpotClick = { onSpotClick(spot) },
                                onRouteClick = onRouteToSpot?.let { handler -> { handler(spot) } },
                                origin = origin,
                            )
                        }
                    }
                }
            }
        }
        SheetNavTabBar(
            selected = if (showFavoritesOnly) GoogleNavTab.SAVED else GoogleNavTab.EXPLORE,
            onLocalSelect = { tab ->
                when (tab) {
                    GoogleNavTab.EXPLORE -> { showFavoritesOnly = false; true }
                    GoogleNavTab.SAVED -> { showFavoritesOnly = true; true }
                    GoogleNavTab.RECORD -> false
                }
            },
        )
      }
    }
}

@Composable
private fun SpotChip(
    cat: SpotQuickCategory,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(cat.poi.iconRes),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(cat.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
