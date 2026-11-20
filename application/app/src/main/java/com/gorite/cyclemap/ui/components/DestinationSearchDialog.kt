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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gorite.cyclemap.data.FavoriteSpot
import com.gorite.cyclemap.data.FavoritesManager
import com.gorite.cyclemap.data.Fts5SupportDetector
import com.gorite.cyclemap.data.SearchDbSelection
import com.gorite.cyclemap.data.SearchDbSelector
import com.gorite.cyclemap.data.SearchHistoryEntry
import com.gorite.cyclemap.data.SearchHistoryManager
import com.gorite.cyclemap.search.GenreDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
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
    val historyManager = remember(context) { SearchHistoryManager.getInstance(context) }
    val history by historyManager.history.collectAsState()
    val selectResult: (SearchResult) -> Unit = { result ->
        historyManager.add(SearchHistoryEntry(result.name, result.category, result.latitude, result.longitude, result.address))
        onResultSelected(result)
    }

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
    val searchDatabases = remember(searchDb) { availableSearchDatabases(cycleMapDataDir(context), searchDb) }
    val unsupportedPrefecture = searchDbInfo.second
    val request = remember(query, selectedCategory, coarseSearchOrigin(effectiveLocation), searchDatabases) {
        DestinationSearchRequest(query, selectedCategory, coarseSearchOrigin(effectiveLocation), searchDatabases.map { it.file.absolutePath })
    }
    var completedRequest by remember { mutableStateOf<DestinationSearchRequest?>(null) }
    val resultsEnabled = completedRequest == request

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

    LaunchedEffect(request) {
        val trimmed = query.trim()
        // カテゴリ未選択 + クエリ短い場合はリストクリア
        if (trimmed.length < 2 && selectedCategory == SearchCategory.ALL) {
            results = emptyList()
            completedRequest = request
            message = when {
                unsupportedPrefecture != null -> "${unsupportedPrefecture}の検索データはありません"
                searchDatabases.isNotEmpty() -> null
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
        val found = searchAcrossPrefectures(
            databases = searchDatabases,
            query = if (genericPrefixes != null) "" else trimmed,
            category = effectiveCategory,
            userLat = uLat,
            userLon = uLon,
            categoryPrefixesOverride = genericPrefixes,
        )
        val ranked = found
        results = ranked
        completedRequest = request
        message = when {
            unsupportedPrefecture != null -> "${unsupportedPrefecture}の検索データはありません"
            searchDatabases.isEmpty() -> "この県の検索データが端末に入っていません (メニュー「オフラインデータ」で確認)"
            ranked.isEmpty() -> "該当する目的地がありません"
            else -> null
        }
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        contentVisible = true
    }

    BackHandler(onBack = onDismiss)

    // 親指で使う検索。押すところと読むところをすべて下に寄せる:
    //   上〜中: 候補 (いちばん近い・合うものを下に、親指の近くに積む) / 下: 種類の札 → 並び順 → 検索窓 (キーボードのすぐ上)
    val sortedByDistance = query.trim().length < 2 || isGenericQuery
    val listState = rememberLazyListState()
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    LaunchedEffect(results) {
        if (results.isNotEmpty()) {
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(0)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress && !isProgrammaticScroll }
            .distinctUntilChanged()
            .filter { it }
            .collect { keyboardController?.hide() }
    }

    val quickCategories = remember { SearchCategory.entries.filter { it != SearchCategory.ALL } }

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
                        query.isEmpty() && selectedCategory == SearchCategory.ALL -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                                // reverseLayout: newest entry is closest to the search field.
                                items(history.take(5)) { entry ->
                                    SearchHistoryItemCard(
                                        entry = entry,
                                        origin = effectiveLocation,
                                        onSelected = { selectResult(entry.toSearchResult()) },
                                        onRemove = { historyManager.remove(entry) },
                                    )
                                }
                                if (history.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_lucide_history),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "最近の行き先",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f),
                                            )
                                            androidx.compose.material3.TextButton(onClick = { historyManager.clear() }) {
                                                Text("すべて消す")
                                            }
                                        }
                                    }
                                }
                                if (favorites.isNotEmpty()) {
                                    items(favorites, key = { it.id }) { fav ->
                                        FavoriteSpotItemCard(
                                            spot = fav,
                                            onSpotSelected = { spot ->
                                                selectResult(
                                                    SearchResult(
                                                        name = spot.name,
                                                        category = spot.category,
                                                        latitude = spot.latitude,
                                                        longitude = spot.longitude,
                                                        distanceMeters = null,
                                                        address = spot.address,
                                                    ),
                                                )
                                            },
                                            onRemoveFavorite = { favoritesManager.removeFavorite(fav.id) },
                                            origin = effectiveLocation,
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
                                } else {
                                    item {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = 12.dp),
                                        ) {
                                            Text(
                                                text = "近くを探す",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp),
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            quickCategories.chunked(4).forEachIndexed { rowIndex, rowCategories ->
                                                if (rowIndex > 0) {
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    for (colIndex in 0 until 4) {
                                                        if (colIndex < rowCategories.size) {
                                                            val cat = rowCategories[colIndex]
                                                            Surface(
                                                                shape = RoundedCornerShape(12.dp),
                                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                                modifier = Modifier
                                                                    .weight(1f)
                                                                    .height(56.dp)
                                                                    .clip(RoundedCornerShape(12.dp))
                                                                    .clickable {
                                                                        selectedCategory = cat
                                                                        keyboardController?.hide()
                                                                    },
                                                            ) {
                                                                Column(
                                                                    modifier = Modifier
                                                                        .fillMaxSize()
                                                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                                    verticalArrangement = Arrangement.Center,
                                                                ) {
                                                                    cat.iconRes()?.let { iconId ->
                                                                        Icon(
                                                                            painter = painterResource(iconId),
                                                                            contentDescription = null,
                                                                            tint = MaterialTheme.colorScheme.onSurface,
                                                                            modifier = Modifier.size(20.dp),
                                                                        )
                                                                        Spacer(modifier = Modifier.height(4.dp))
                                                                    }
                                                                    Text(
                                                                        text = cat.label,
                                                                        fontSize = 12.sp,
                                                                        color = MaterialTheme.colorScheme.onSurface,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis,
                                                                    )
                                                                }
                                                            }
                                                        } else {
                                                            Spacer(modifier = Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "例: 広島駅 セブン トイレ",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                                itemsIndexed(results) { index, result ->
                                  // reverseLayout では item 内の並びも逆になるので、県名とカードを Column にまとめて県名を上に固定する
                                  androidx.compose.foundation.layout.Column {
                                    result.prefectureName?.let { prefecture ->
                                        Text(prefecture, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 12.dp))
                                    }
                                    SearchResultItemCard(
                                        result = result,
                                        enabled = resultsEnabled,
                                        isFavorite = favoritesManager.isFavorite(result.latitude, result.longitude),
                                        onResultSelected = {
                                            if (completedRequest == request && query == request.query && selectedCategory == request.category) {
                                                selectResult(it)
                                            }
                                        },
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
                                        // 距離の横に方角 (北東など) を出すための現在地
                                        origin = effectiveLocation,
                                    )
                                  }
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

            // 種類の札 (横に流せる・親指の届く下側へ配置)
            // 文字を入れている間 (種類を選んでいないとき) は隠し、キーボードの上に見える候補を 1 件でも多くする
            if (query.isEmpty() || selectedCategory != SearchCategory.ALL) Row(
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
                            .clickable {
                                selectedCategory = cat
                                keyboardController?.hide()
                            },
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

private fun SearchHistoryEntry.toSearchResult() = SearchResult(
    name = name,
    category = category,
    latitude = latitude,
    longitude = longitude,
    address = address,
)

@Composable
private fun SearchHistoryItemCard(
    entry: SearchHistoryEntry,
    origin: Pair<Double, Double>?,
    onSelected: () -> Unit,
    onRemove: () -> Unit,
) {
    val distance = origin?.let { haversineMeters(it.first, it.second, entry.latitude, entry.longitude) }
    val bearing = origin?.let { initialBearingDeg(it.first, it.second, entry.latitude, entry.longitude) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSelected)
                .heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.name,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DistanceAndDirection(distance, bearing)
                }
                Text(
                    listOfNotNull(
                        formatCategoryLabel(entry.category),
                        entry.address?.let { shortAddress(it) }?.takeIf { it.isNotBlank() },
                    ).joinToString(" ・ "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_lucide_x),
                    contentDescription = "「${entry.name}」を履歴から削除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun SearchCategory.iconRes(): Int? = when (this) {
    SearchCategory.ALL -> null
    SearchCategory.CONVENIENCE -> R.drawable.ic_lucide_store
    SearchCategory.BICYCLE -> R.drawable.ic_poi_bike
    SearchCategory.STATION -> R.drawable.ic_poi_train
    SearchCategory.TOILET -> R.drawable.ic_lucide_toilet
    SearchCategory.FOOD -> R.drawable.ic_lucide_utensils
    SearchCategory.TOURISM -> R.drawable.ic_lucide_camera
    SearchCategory.BATH -> R.drawable.ic_poi_onsen
}
