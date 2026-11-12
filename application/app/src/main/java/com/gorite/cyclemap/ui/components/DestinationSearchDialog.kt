package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.gorite.cyclemap.R
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.search.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.data.FavoriteSpot
import com.gorite.cyclemap.data.FavoritesManager
import com.gorite.cyclemap.data.Fts5SupportDetector
import com.gorite.cyclemap.data.SearchDbSelection
import com.gorite.cyclemap.data.SearchDbSelector
import com.gorite.cyclemap.search.GenreDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
internal fun DestinationSearchDialog(
    context: Context,
    liveLocation: Pair<Double, Double>?,
    onDismiss: () -> Unit,
    onResultSelected: (SearchResult) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(SearchCategory.ALL) }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    /** 検索語が種類名だけ (「コンビニ」など) で、近い順の一覧を出しているとき true。 */
    var isGenericQuery by remember { mutableStateOf(false) }
    var showFts5Notice by remember { mutableStateOf(false) }

    val favoritesManager = remember(context) { FavoritesManager.getInstance(context) }
    val favorites by favoritesManager.favorites.collectAsState()

    // 現在地を取得（位置情報許可済みの場合）
    val userLocation = remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // live位置を優先し、無ければ最終既知位置を使う。
    val effectiveLocation = liveLocation ?: userLocation.value
    // 県別search.db選択。位置確定前は従来の search.db、未対応県では番兵＋県名を保持する。
    val searchDbInfo = remember(context, effectiveLocation) {
        val dataDir = cycleMapDataDir(context)
        val loc = effectiveLocation
        if (loc == null) {
            val f = SearchDbSelector.findFirstAvailableDb(dataDir) ?: File(dataDir, "search.db")
            Pair(f, null)
        } else {
            when (val sel = SearchDbSelector.select(loc.first, loc.second)) {
                is SearchDbSelection.Available -> {
                    val f = SearchDbSelector.resolveDbFile(dataDir, sel.fileName) ?: File(dataDir, sel.fileName)
                    f.also {
                        Log.i("CycleMapGeo", "POI_DB prefecture=${sel.prefectureName} file=${it.name} exists=${it.isFile}")
                    }.let { Pair(it, null) }
                }
                is SearchDbSelection.Unavailable -> {
                    Log.w("CycleMapGeo", "POI_DB reason=unsupported prefecture=${sel.prefectureName ?: "(unknown)"}")
                    Pair(File(dataDir, "search_unsupported.db"), sel.prefectureName ?: "この地域")
                }
            }
        }
    }
    val searchDb = searchDbInfo.first
    val unsupportedPrefecture = searchDbInfo.second

    // FTS5およびsearch_textの対応状況をチェック (旧DBのみセッション1回案内)
    LaunchedEffect(searchDb) {
        if (searchDb.isFile) {
            withContext(Dispatchers.IO) {
                runCatching {
                    SQLiteDatabase.openDatabase(searchDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        val ftsSupported = Fts5SupportDetector.isSupported(db)
                        val hasSearchText = hasSearchTextColumn(db)
                        val shouldShow = shouldShowFtsNotice(
                            ftsSupported = ftsSupported,
                            hasSearchText = hasSearchText,
                            hasShownThisSession = hasShownFts5NoticeThisSession,
                        )
                        if (shouldShow) {
                            hasShownFts5NoticeThisSession = true
                            withContext(Dispatchers.Main) {
                                showFts5Notice = true
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (context.hasLocationPermission()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val lm = context.getSystemService(android.location.LocationManager::class.java)
                    val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        val loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                        loc?.let { userLocation.value = it.latitude to it.longitude }
                    }
                }
            }
        }
    }

    LaunchedEffect(query, selectedCategory, effectiveLocation) {
        val trimmed = query.trim()
        // カテゴリ未選択 + クエリ短い場合はリストクリア
        if (trimmed.length < 2 && selectedCategory == SearchCategory.ALL) {
            results = emptyList()
            message = when {
                unsupportedPrefecture != null -> "${unsupportedPrefecture}の検索データはありません (対応は中国5県)"
                searchDb.isFile -> null
                else -> "この県の検索データが端末に入っていません (メニュー「オフラインデータ」で確認)"
            }
            return@LaunchedEffect
        }
        val (uLat, uLon) = effectiveLocation?.let { it.first to it.second } ?: (null to null)
        // 「コンビニ」「近くのトイレ」のように語全体が種類名なら、名前では探さず、その種類の近い順の一覧にする
        // (名前に「コンビニ」を含む店を探すと 0 件になるため)。
        val genericPrefixes = GenreDictionary.genericCategoryPrefixes(trimmed)
        isGenericQuery = genericPrefixes != null
        val genre = GenreDictionary.matchGenre(trimmed)
        val effectiveCategory = if (selectedCategory == SearchCategory.ALL && genre != null) {
            when (genre.id) {
                "convenience" -> SearchCategory.CONVENIENCE
                "station" -> SearchCategory.STATION
                "toilet" -> SearchCategory.TOILET
                "cafe_restaurant" -> SearchCategory.FOOD
                else -> selectedCategory
            }
        } else {
            selectedCategory
        }
        val found = withContext(Dispatchers.IO) {
            if (genericPrefixes != null) {
                searchPlaces(
                    dbFile = searchDb,
                    query = "",
                    userLat = uLat,
                    userLon = uLon,
                    categoryPrefixesOverride = genericPrefixes,
                )
            } else {
                searchPlaces(
                    dbFile = searchDb,
                    query = trimmed,
                    category = effectiveCategory,
                    userLat = uLat,
                    userLon = uLon,
                )
            }
        }
        // 並び順は searchPlaces が一致度・重要度・距離の総合点で決めている (ここで並べ直さない)
        val ranked = found
        results = ranked
        message = when {
            unsupportedPrefecture != null -> "${unsupportedPrefecture}の検索データはありません (対応は中国5県)"
            !searchDb.isFile -> "この県の検索データが端末に入っていません (メニュー「オフラインデータ」で確認)"
            ranked.isEmpty() -> "該当する目的地がありません"
            else -> null
        }
    }

    val focusRequester = remember { FocusRequester() }
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        contentVisible = true
    }

    BackHandler(onBack = onDismiss)

    // 親指で使う検索 (UI 総合版の「肆」)。押すところと読むところを下に寄せる:
    //   上: 種類の札 / 中: 候補 (いちばん近い・合うものを下に、親指の近くに積む) / 下: 検索窓 (キーボードのすぐ上)
    val sortedByDistance = query.trim().length < 2 || isGenericQuery
    val listState = rememberLazyListState()
    LaunchedEffect(results) { if (results.isNotEmpty()) listState.scrollToItem(0) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .navigationBarsPadding()
            .imePadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // 上: 種類の札 (横に流せる)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchCategory.entries.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) CycleMapTheme.palette.accentContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = if (isSelected) BorderStroke(1.dp, CycleMapTheme.palette.accent) else null,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { selectedCategory = cat },
                    ) {
                        Text(
                            cat.label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) CycleMapTheme.palette.onAccentContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            // FTS5非対応時の案内バナー（セッション中1回のみ）
            if (showFts5Notice) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CycleMapTheme.palette.warningContainer),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_lucide_info),
                            contentDescription = null,
                            tint = CycleMapTheme.palette.warning,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "旧形式の検索DBが使用されています。漢字または前方・部分一致で検索してください",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showFts5Notice = false }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                painterResource(R.drawable.ic_lucide_x),
                                contentDescription = "閉じる",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // 中: 候補。reverseLayout で先頭 (いちばん近い・合うもの) を下端に置き、遠いものほど上へ積む
            AnimatedVisibility(
                visible = contentVisible,
                enter = slideInVertically(
                    initialOffsetY = { it / 4 },
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    when {
                        query.isEmpty() && results.isEmpty() && favorites.isNotEmpty() -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                                items(favorites, key = { it.id }) { fav ->
                                    FavoriteSpotItemCard(
                                        spot = fav,
                                        onSpotSelected = { spot ->
                                            onResultSelected(
                                                SearchResult(
                                                    name = spot.name,
                                                    category = spot.category,
                                                    latitude = spot.latitude,
                                                    longitude = spot.longitude,
                                                    distanceMeters = null,
                                                ),
                                            )
                                        },
                                        onRemoveFavorite = { favoritesManager.removeFavorite(fav.id) },
                                    )
                                }
                                item {
                                    Text(
                                        "★ お気に入り (${favorites.size}件)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = CycleMapTheme.palette.favorite,
                                        modifier = Modifier.padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                        query.isEmpty() && results.isEmpty() -> {
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_lucide_search),
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "下の欄に行き先を入れてください",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                                itemsIndexed(results) { index, result ->
                                    SearchResultItemCard(
                                        result = result,
                                        isFavorite = favoritesManager.isFavorite(result.latitude, result.longitude),
                                        onResultSelected = onResultSelected,
                                        onToggleFavorite = {
                                            favoritesManager.toggleFavorite(
                                                FavoriteSpot(
                                                    id = "${result.name}_${result.latitude}_${result.longitude}",
                                                    name = result.name,
                                                    category = result.category,
                                                    latitude = result.latitude,
                                                    longitude = result.longitude,
                                                ),
                                            )
                                        },
                                        highlightLabel = if (index == 0) {
                                            if (sortedByDistance) "いちばん近い" else "いちばん合う"
                                        } else {
                                            null
                                        },
                                    )
                                }
                                if (results.size > 1) {
                                    item {
                                        Text(
                                            if (sortedByDistance) "↑ 上ほど遠い" else "↑ 上ほど合わない",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 並び順・件数・案内 (検索窓のすぐ上)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (effectiveLocation != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_lucide_map_pin),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            // 検索語があるときは一致度・重要度・距離の総合順、カテゴリだけのときは近い順
                            if (!sortedByDistance) "関連度順 (近さも考慮)" else "現在地から近い順",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    Text("現在地なし", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (results.isNotEmpty()) {
                    Text("${results.size}件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                )
            }

            // 下: 検索窓 (キーボードのすぐ上、親指の位置)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
                    .height(54.dp),
                shape = RoundedCornerShape(27.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_google_arrow_back),
                            contentDescription = "戻る",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            decorationBox = { innerTextField ->
                                if (query.isEmpty()) {
                                    Text("どこへ行く？", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                innerTextField()
                            },
                        )
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { query = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_x),
                                contentDescription = "クリア",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
