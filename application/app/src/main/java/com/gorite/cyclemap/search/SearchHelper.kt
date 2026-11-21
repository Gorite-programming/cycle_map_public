package com.gorite.cyclemap.search

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.gorite.cyclemap.data.Fts5SupportDetector
import com.gorite.cyclemap.utils.*
import java.io.File

// ---------------------------------------------------------------------------
// 検索データモデル
// ---------------------------------------------------------------------------

internal data class SearchResult(
    val name: String,
    val category: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double? = null,
    /** 住所 (検索DB の address 列)。同じ名前の支店を見分けるのに表示する。 */
    val address: String? = null,
    /** 検索用の別表記 (search_text 列)。読みでの一致を点数に入れるのに使う。 */
    val searchText: String? = null,
    val prefectureName: String? = null,
)

/** 住所で検索したときに作る結果のカテゴリ (DB の行ではなく、住所が一致した地点の代表点)。 */
internal const val ADDRESS_RESULT_CATEGORY = "address"


/** カテゴリフィルタタブの定義 */
internal enum class SearchCategory(val label: String, val categoryPrefixes: List<String>) {
    ALL("すべて", emptyList()),
    CONVENIENCE("コンビニ", listOf("shop:convenience")),
    BICYCLE("自転車", listOf("shop:bicycle")),
    STATION("駅・バス停", listOf("railway:station", "public_transport:station", "amenity:bus_station", "highway:bus_stop", "public_transport:stop_position")),
    TOILET("トイレ", listOf("amenity:toilets")),
    FOOD("飲食", listOf("amenity:restaurant", "amenity:cafe", "amenity:fast_food")),
    TOURISM("観光", listOf("tourism:")),
    BATH("温泉", listOf("amenity:public_bath")),
}

/**
 * OSMカテゴリ文字列を直感的な日本語ラベルに変換する。
 */
fun formatCategoryLabel(category: String): String {
    return when {
        category.startsWith("railway:station") -> "鉄道駅"
        category.startsWith("railway:halt") -> "駅(無人駅)"
        category.startsWith("public_transport:station") -> "駅・ターミナル"
        category.startsWith("highway:bus_stop") -> "バス停"
        category.startsWith("amenity:bus") -> "バスターミナル"
        category.startsWith("public_transport:stop_position") -> "停留所"
        category.startsWith("shop:convenience") -> "コンビニ"
        category.startsWith("shop:supermarket") -> "スーパー"
        category.startsWith("shop:bicycle") -> "自転車店"
        category.startsWith("shop:bakery") -> "パン屋"
        category.startsWith("amenity:public_bath") -> "温泉・銭湯"
        category.startsWith("amenity:toilets") -> "トイレ"
        category.startsWith("amenity:drinking_water") -> "給水スポット"
        category.startsWith("amenity:vending_machine") -> "自販機"
        category.startsWith("tourism:road_station") -> "道の駅"
        category.startsWith("amenity:place_of_worship") -> "寺社・休憩所"
        category.startsWith("amenity:restaurant") -> "飲食店"
        category.startsWith("amenity:cafe") -> "カフェ"
        category.startsWith("amenity:fast_food") -> "ファストフード"
        category.startsWith("tourism:attraction") -> "観光名所"
        category.startsWith("tourism:viewpoint") -> "展望台"
        category.startsWith("tourism:museum") -> "博物館・美術館"
        category.startsWith("tourism:") -> "観光"
        category.startsWith("amenity:parking") -> "駐車場"
        category.startsWith("amenity:hospital") -> "総合病院"
        category.startsWith("amenity:clinic") || category.startsWith("amenity:doctors") -> "クリニック"
        category.startsWith("amenity:dentist") -> "歯科医院"
        category.startsWith("amenity:fuel") -> "ガソリンスタンド"
        category.startsWith("leisure:park") -> "公園"
        category.startsWith("amenity:drinking_water") -> "給水スポット"
        category.startsWith("amenity:post_office") -> "郵便局"
        category.startsWith("amenity:bank") || category.startsWith("amenity:atm") -> "銀行・ATM"
        category.startsWith("amenity:school") -> "学校"
        category.startsWith("amenity:university") || category.startsWith("amenity:college") -> "大学・高専"
        category.startsWith("amenity:library") -> "図書館"
        category.startsWith("amenity:police") -> "警察・交番"
        category.startsWith("amenity:fire_station") -> "消防署"
        category.startsWith("place:city") || category.startsWith("place:town") -> "市区町村"
        category == ADDRESS_RESULT_CATEGORY -> "住所"
        category == com.gorite.cyclemap.data.CUSTOM_FAVORITE_CATEGORY -> "登録した地点"
        category.startsWith("shop:mall") || category.startsWith("shop:department_store") -> "ショッピングモール"
        category.startsWith("amenity:bicycle_rental") -> "レンタサイクル"
        category.startsWith("amenity:bicycle_parking") -> "駐輪場"
        category.startsWith("amenity:charging_station") -> "充電スポット"
        category.startsWith("amenity:pharmacy") || category.startsWith("shop:chemist") -> "薬局・ドラッグストア"
        category.startsWith("amenity:bar") || category.startsWith("amenity:pub") -> "バー・居酒屋"
        category.startsWith("amenity:townhall") -> "役所"
        category.startsWith("amenity:kindergarten") -> "幼稚園・保育園"
        category.startsWith("amenity:ferry_terminal") -> "フェリー乗り場"
        category.startsWith("tourism:hotel") || category.startsWith("tourism:guest_house") ||
            category.startsWith("tourism:hostel") || category.startsWith("tourism:motel") -> "宿泊"
        category.startsWith("tourism:camp_site") -> "キャンプ場"
        category.startsWith("leisure:stadium") || category.startsWith("leisure:sports_centre") -> "スポーツ施設"
        category.startsWith("leisure:") -> "レジャー"
        category.startsWith("historic:") -> "史跡"
        category.startsWith("natural:") -> "自然"
        category.startsWith("railway:") -> "鉄道"
        category.startsWith("public_transport:") -> "交通"
        category.startsWith("place:") -> "地名"
        category.startsWith("shop:") -> "お店"
        category.startsWith("amenity:") || category.startsWith("office:") ||
            category.startsWith("craft:") || category.startsWith("man_made:") -> "施設"
        else -> "スポット" // "named" など、種類の分からない地点
    }
}

/**
 * 「〜駅」「〜えき」「〜eki」検索クエリからベース駅名（例: "広島駅" → "広島"）を抽出する。
 */
@VisibleForTesting
internal fun extractStationQuery(query: String): String? {
    val trimmed = query.trim()
    return when {
        trimmed.endsWith("駅") && trimmed.length > 1 -> trimmed.removeSuffix("駅").trim()
        trimmed.endsWith("えき") && trimmed.length > 2 -> trimmed.removeSuffix("えき").trim()
        trimmed.lowercase().endsWith("eki") && trimmed.length > 3 -> trimmed.dropLast(3).trim()
        else -> null
    }
}

/**
 * カテゴリの優先度ランク（数値が小さいほど高優先度）。
 */
@VisibleForTesting
internal fun categoryPriority(category: String): Int {
    return when {
        category.startsWith("railway:station") ||
            category.startsWith("public_transport:station") -> 1 // 鉄道駅・ターミナル
        category.startsWith("place:") -> 2 // 自治体・地名
        category.startsWith("tourism:") ||
            category.startsWith("shop:") ||
            category.startsWith("amenity:restaurant") ||
            category.startsWith("amenity:cafe") ||
            category.startsWith("amenity:fast_food") ||
            category.startsWith("amenity:hospital") ||
            category.startsWith("amenity:clinic") -> 3 // 施設・店舗
        category.startsWith("amenity:toilets") ||
            category.startsWith("amenity:drinking_water") ||
            category.startsWith("leisure:park") -> 4 // トイレ・給水・公園
        category.startsWith("highway:bus_stop") ||
            category.startsWith("amenity:bus") ||
            category.startsWith("public_transport:stop_position") -> 5 // バス停
        else -> 4
    }
}

/**
 * 同一名称で近接（デフォルト300m以内）するバス停を代表1件にクラスタリング（集約）する。
 */
@VisibleForTesting
internal fun clusterBusStops(
    items: List<SearchResult>,
    clusterRadiusM: Double = 300.0,
): List<SearchResult> {
    val isBusStop = { r: SearchResult ->
        r.category.startsWith("highway:bus_stop") ||
            r.category.startsWith("amenity:bus") ||
            r.category.startsWith("public_transport:stop_position")
    }

    val nonBusStops = mutableListOf<SearchResult>()
    val busStops = mutableListOf<SearchResult>()

    for (item in items) {
        if (isBusStop(item)) {
            busStops.add(item)
        } else {
            nonBusStops.add(item)
        }
    }

    val clusteredBusStops = mutableListOf<SearchResult>()
    for (bus in busStops) {
        val existing = clusteredBusStops.firstOrNull {
            it.name == bus.name && haversineMeters(it.latitude, it.longitude, bus.latitude, bus.longitude) <= clusterRadiusM
        }
        if (existing == null) {
            clusteredBusStops.add(bus)
        }
    }

    return nonBusStops + clusteredBusStops
}

/**
 * 検索語との一致度 (0..1000)。名前の完全一致 > 読み・別表記の完全一致 > 前方一致 > 部分一致。
 * 「〜駅」の検索で、駅名だけで登録された鉄道駅 (「広島」) は名前の完全一致に準じる。
 */
@VisibleForTesting
internal fun textMatchScore(result: SearchResult, query: String): Double {
    val q = SearchNormalizer.normalize(query)
    if (q.isEmpty()) return 0.0
    val qCompact = q.replace(" ", "")
    val name = SearchNormalizer.normalize(result.name)
    val nameCompact = name.replace(" ", "")
    val tokens = SearchNormalizer.normalize(result.searchText ?: "").split(' ').filter { it.isNotEmpty() }
    val stationBase = extractStationQuery(query.trim())?.let { SearchNormalizer.normalize(it) }
    val isStation = result.category.startsWith("railway:station") || result.category.startsWith("public_transport:station") ||
        result.category.startsWith("railway:halt")
    return when {
        name == q || nameCompact == qCompact -> 1000.0
        // 駅名だけで登録された駅 (OSM の「広島」) は、名前の完全一致と同じに扱う (差は距離で決まる)
        stationBase != null && isStation && (name == stationBase || tokens.contains(stationBase)) -> 1000.0
        tokens.any { it == q || it == qCompact } -> 800.0
        nameCompact.startsWith(qCompact) -> 600.0
        tokens.any { it.startsWith(qCompact) } -> 500.0
        nameCompact.contains(qCompact) -> 300.0
        tokens.any { it.contains(qCompact) } -> 150.0
        stationBase != null && isStation && name.contains(stationBase) -> 150.0
        else -> 50.0 // FTS などで当たったが、文字列としては一致が弱いもの
    }
}

/** 場所の重要度による加点 (駅・地名・名所は上げ、バス停・種類不明は下げる)。 */
@VisibleForTesting
internal fun prominenceBonus(category: String): Double = when {
    category == ADDRESS_RESULT_CATEGORY -> 120.0
    category.startsWith("railway:station") || category.startsWith("public_transport:station") -> 200.0
    category.startsWith("place:city") || category.startsWith("place:town") -> 180.0
    category.startsWith("place:") -> 120.0
    category.startsWith("tourism:attraction") || category.startsWith("tourism:museum") ||
        category.startsWith("tourism:viewpoint") || category.startsWith("tourism:theme_park") ||
        category.startsWith("tourism:zoo") || category.startsWith("tourism:aquarium") -> 120.0
    category.startsWith("amenity:university") || category.startsWith("amenity:college") ||
        category.startsWith("amenity:hospital") || category.startsWith("amenity:townhall") ||
        category.startsWith("railway:halt") || category.startsWith("amenity:ferry_terminal") -> 80.0
    else -> when (categoryPriority(category)) {
        3 -> 40.0
        5 -> -150.0
        else -> 0.0
    } + if (category == "named") -30.0 else 0.0
}

/** 距離による減点。1km で約 24、10km で約 83、50km で約 137。遠方の目的地も消えない程度に緩やか。 */
@VisibleForTesting
internal fun distancePenalty(distanceMeters: Double?): Double =
    if (distanceMeters == null) 0.0 else 80.0 * kotlin.math.log10(1.0 + distanceMeters / 1000.0)

/** 1 件の総合点。別名 ([queries] の 2 件目以降) での一致は少し下げる。 */
@VisibleForTesting
internal fun searchScore(result: SearchResult, queries: List<String>): Double {
    val text = queries.withIndex().maxOf { (i, q) -> textMatchScore(result, q) * if (i == 0) 1.0 else 0.95 }
    return text + prominenceBonus(result.category) - distancePenalty(result.distanceMeters)
}

/** 鉄道駅として扱うカテゴリか (カテゴリ不明やバス停は含めない)。 */
private fun isRailwayStationCategory(category: String): Boolean =
    category.startsWith("railway:station") ||
        category.startsWith("railway:halt") ||
        category.startsWith("public_transport:station")

/** 正規化・空白除去済みの 2 つの駅名が「末尾の『駅』の有無」だけで一致するか。 */
private fun isStationSuffixVariant(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    return (a.endsWith("駅") && a.length > 1 && a.removeSuffix("駅") == b) ||
        (b.endsWith("駅") && b.length > 1 && b.removeSuffix("駅") == a)
}

/**
 * 同じ名前で近い (既定 150m 以内) 結果を 1 件にまとめる。複数の出典 (OSM・Overture など) の
 * 同じ場所や、駅の複数のホームが重なって並ぶのを防ぐ。[items] は良い順に並んでいること (先の方を残す)。
 * また、2 件とも鉄道駅カテゴリで距離が [stationRadiusM] (既定 50m) 以内かつ末尾の「駅」の有無だけで
 * 名前が一致する場合 (例:「岩国」と「岩国駅」) も同一施設として先の方を残す。
 */
@VisibleForTesting
internal fun dedupeNearbySameName(
    items: List<SearchResult>,
    radiusM: Double = 150.0,
    stationRadiusM: Double = 50.0,
): List<SearchResult> {
    val kept = ArrayList<SearchResult>(items.size)
    val keptKeys = ArrayList<String>(items.size)
    for (r in items) {
        val key = SearchNormalizer.normalize(r.name).replace(" ", "")
        val rIsStation = isRailwayStationCategory(r.category)
        val dup = kept.indices.any { idx ->
            val existing = kept[idx]
            val existingKey = keptKeys[idx]
            val dist = haversineMeters(existing.latitude, existing.longitude, r.latitude, r.longitude)
            (existingKey == key && dist <= radiusM) ||
                (rIsStation && isRailwayStationCategory(existing.category) && dist <= stationRadiusM && isStationSuffixVariant(existingKey, key))
        }
        if (!dup) {
            kept += r
            keptKeys += key
        }
    }
    return kept
}

/**
 * 検索結果を、一致度・場所の重要度・距離の総合点で並べる (Google マップのように「関連が強く近いもの」を上に)。
 * 別名 (略称・英字) も一致度の計算に使う。同名で近いものは 1 件にまとめる。
 */
@VisibleForTesting
internal fun rankSearchResults(
    items: List<SearchResult>,
    query: String,
    userLat: Double?,
    userLon: Double?,
): List<SearchResult> {
    val queries = SearchAliases.expand(query).ifEmpty { listOf(query) }
    val withDistance = items.map { r ->
        if (r.distanceMeters == null && userLat != null && userLon != null) {
            r.copy(distanceMeters = haversineMeters(userLat, userLon, r.latitude, r.longitude))
        } else r
    }
    val sorted = withDistance
        .map { it to searchScore(it, queries) }
        .sortedWith(compareByDescending<Pair<SearchResult, Double>> { it.second }.thenBy { it.first.distanceMeters ?: Double.MAX_VALUE })
        .map { it.first }
    return dedupeNearbySameName(sorted)
}

private val placesColumnsCache = FileVersionCache<Set<String>>()

/** places テーブルの列名 (DB ごとにキャッシュ)。 */
internal fun placesColumns(db: SQLiteDatabase): Set<String> {
    fun read(): Set<String> = try {
        db.searchQuery("PRAGMA table_info(places)", null).use { c ->
            val idx = c.getColumnIndex("name")
            buildSet { while (c.moveToNext()) if (idx >= 0) add(c.getString(idx)) }
        }
    } catch (e: android.os.OperationCanceledException) {
        throw e
    } catch (e: Exception) {
        emptySet()
    }
    return placesColumnsCache.getOrLoad(db.path) { read() }
}

/**
 * 距離に関係なく「一致の強いもの」を拾う: 名前の完全一致・前方一致、読み・別表記の完全一致。
 * 距離順の LIMIT で遠くの正解 (隣の市の駅など) が落ちるのを防ぐ。件数は少数に抑える。
 */
private fun fetchStrongMatches(
    db: SQLiteDatabase,
    query: String,
    hasSearchText: Boolean,
    extraSelect: String,
    categoryWherePlain: String,
    categoryArgs: List<String>,
    add: (android.database.Cursor) -> Unit,
) {
    val q = query.trim()
    if (q.length < 2) return
    val nq = SearchNormalizer.normalize(q)
    val (where, args) = if (hasSearchText) {
        "(name = ? OR name LIKE ? ESCAPE '\\' OR (' ' || search_text || ' ') LIKE ? ESCAPE '\\')" to
            listOf(q, "${escapeLike(q)}%", "% ${escapeLike(nq)} %")
    } else {
        "(name = ? OR name LIKE ? ESCAPE '\\')" to listOf(q, "${escapeLike(q)}%")
    }
    try {
        db.searchQuery(
            """
            SELECT name, category, lat, lon $extraSelect
            FROM places
            WHERE $where $categoryWherePlain
            ORDER BY length(name)
            LIMIT 40
            """.trimIndent(),
            (args + categoryArgs).toTypedArray(),
        ).use { c -> while (c.moveToNext()) add(c) }
    } catch (e: SQLiteException) {
        Log.w("CycleMap", "strong match query failed: ${e.message}")
    }
}

/** 住所らしい検索語か (数字・丁目・番地、または市区町村名を含む)。 */
@VisibleForTesting
internal fun looksLikeAddress(query: String): Boolean {
    val q = query.trim()
    return q.any { it.isDigit() || it in '０'..'９' } || Regex("(丁目|番地|番|号|市|区|町|村)").containsMatchIn(q)
}

/**
 * 住所で探す。検索DB の address 列に検索語を含む地点を集め、最初に見つかった地点の近く (300m) の
 * 平均位置を代表点として返す。住所らしくない語や、一致が無いときは null。
 */
private fun addressResult(db: SQLiteDatabase, query: String): SearchResult? {
    if (!looksLikeAddress(query)) return null
    val q = SearchNormalizer.normalize(query).replace(" ", "")
    if (q.length < 2) return null
    val rows: List<Triple<String, Double, Double>> = try {
        db.searchQuery(
            "SELECT address, lat, lon FROM places WHERE replace(address, ' ', '') LIKE ? ESCAPE '\\' LIMIT 60",
            arrayOf("%${escapeLike(q)}%"),
        ).use { c ->
            val out = ArrayList<Triple<String, Double, Double>>()
            while (c.moveToNext()) out += Triple(c.getString(0) ?: "", c.getDouble(1), c.getDouble(2))
            out
        }
    } catch (e: SQLiteException) {
        Log.w("CycleMap", "address query failed: ${e.message}")
        return null
    }
    if (rows.isEmpty()) return null
    val first = rows.first()
    val near = rows.filter { haversineMeters(first.second, first.third, it.second, it.third) <= 300.0 }
    // 表示名は、一致した住所のうち最も短いもの (= 検索語に最も近い表記)
    val label = near.minBy { it.first.length }.first
    return SearchResult(
        name = label,
        category = ADDRESS_RESULT_CATEGORY,
        latitude = near.map { it.second }.average(),
        longitude = near.map { it.third }.average(),
        address = null,
        searchText = q,
    )
}

private val placesHasSearchTextCache = FileVersionCache<Boolean>()

@VisibleForTesting
internal fun clearPlacesHasSearchTextCacheForTesting() {
    placesHasSearchTextCache.clear()
}

internal fun hasSearchTextColumn(db: SQLiteDatabase): Boolean {
    return placesHasSearchTextCache.getOrLoad(db.path) {
        checkSearchTextColumnDirectly(db)
    }
}

private fun checkSearchTextColumnDirectly(db: SQLiteDatabase): Boolean {
    return try {
        db.searchQuery("PRAGMA table_info(places)", null).use { cursor ->
            val nameCol = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameCol >= 0 && cursor.getString(nameCol) == "search_text") {
                    return true
                }
            }
        }
        false
    } catch (e: android.os.OperationCanceledException) {
        throw e
    } catch (e: Exception) {
        Log.w("CycleMap", "Failed to check table_info for places: ${e.message}")
        false
    }
}

internal var hasShownFts5NoticeThisSession = false

@VisibleForTesting
internal fun resetFts5NoticeForTesting() {
    hasShownFts5NoticeThisSession = false
}

/**
 * FTS5/曖昧検索の注意バナーを表示すべきかを判定する。
 * - FTS5対応端末、または FTS5非対応でも検索DBに search_text 列（ひらがな・カタカナ・ローマ字対応）が存在する場合は false。
 * - FTS5非対応かつ search_text 列が無い旧形式DBの場合のみ、セッション中1回 true を返す。
 */
@VisibleForTesting
internal fun shouldShowFtsNotice(
    ftsSupported: Boolean,
    hasSearchText: Boolean,
    hasShownThisSession: Boolean,
): Boolean {
    if (ftsSupported) return false
    if (hasSearchText) return false
    return !hasShownThisSession
}

@VisibleForTesting
internal fun buildFtsQuery(trimmed: String): String {
    return trimmed
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
}

/** LIKE のワイルドカード (% _) とエスケープ文字 (\\) をリテラルとして扱うためのエスケープ。ESCAPE '\\' とセットで使う。 */
@VisibleForTesting
internal fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

@VisibleForTesting
internal fun buildLikeTextConditions(trimmed: String, targetColumn: String): Pair<String, List<String>> {
    val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
    val clause = "$targetColumn LIKE ? ESCAPE '\\'"
    val whereClause = if (words.isEmpty()) {
        clause
    } else {
        words.joinToString(" AND ") { clause }
    }
    val args = if (words.isEmpty()) {
        listOf("%${escapeLike(trimmed)}%")
    } else {
        words.map { "%${escapeLike(it)}%" }
    }
    return whereClause to args
}

// ---------------------------------------------------------------------------
// 場所・POI検索
// ---------------------------------------------------------------------------

/**
 * 場所を検索する。
 * @param dbFile 検索DB
 * @param query 検索クエリ（空文字の場合はカテゴリ一覧表示）
 * @param category カテゴリフィルタ（ALL以外はカテゴリ絞り込み）
 * @param userLat 現在地緯度（null時は距離計算しない）
 * @param userLon 現在地経度（null時は距離計算しない）
 * @param limit 最大件数
 * @param categoryPrefixesOverride タブにない種類で絞るときの category の接頭辞 (「病院」と入力したときなど)。
 *   指定すると [category] の接頭辞の代わりに使う。
 */
internal fun searchPlaces(
    dbFile: File,
    query: String,
    category: SearchCategory = SearchCategory.ALL,
    userLat: Double? = null,
    userLon: Double? = null,
    limit: Int = 50,
    categoryPrefixesOverride: List<String>? = null,
    restrictCategoryToOrigin: Boolean = true,
): List<SearchResult> {
    if (!dbFile.isFile) return emptyList()

    val categoryPrefixes = categoryPrefixesOverride ?: category.categoryPrefixes
    // カテゴリWHERE句を組み立て
    val categoryWhere: String        // FTS JOIN クエリ用 (p.category)
    val categoryWherePlain: String   // plain places クエリ用 (category、エイリアスなし)
    val categoryArgs: List<String>
    if (categoryPrefixes.isEmpty()) {
        categoryWhere = ""
        categoryWherePlain = ""
        categoryArgs = emptyList()
    } else {
        val clauses = categoryPrefixes.joinToString(" OR ") { "p.category LIKE ?" }
        val clausesPlain = categoryPrefixes.joinToString(" OR ") { "category LIKE ?" }
        categoryWhere = "AND ($clauses)"
        categoryWherePlain = "AND ($clausesPlain)"
        // LIKE 用引数: すべての prefix に % を付ける
        categoryArgs = categoryPrefixes.map { "$it%" }
    }

    val results = linkedMapOf<String, SearchResult>()

    SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val trimmed = query.trim()
        if (trimmed.length >= 2) {
            // 現在地がある場合は距離順 (NEARBY_ORDER_BY) でソートしてから LIMIT を適用。
            // ※ bbox による範囲絞り込みは遠方目的地を壊さないため適用しないが、
            //    距離順ソートは名称検索にも適用することで近傍のチェーン店（セブン等）を確実に拾う。
            //    ただし距離順の LIMIT だけだと、遠くの「名前が完全に一致する場所」(9km 先の広島駅など) が
            //    近くの部分一致に押し出されるので、別途「一致の強いもの」を距離に関係なく拾う (fetchStrongMatches)。
            val orderArgs = if (userLat != null && userLon != null) {
                nearbyOrderArgs(userLat, userLon)
            } else {
                emptyList()
            }
            val orderBy = if (orderArgs.isNotEmpty()) NEARBY_ORDER_BY else ""
            val columns = placesColumns(db)
            val hasSearchText = "search_text" in columns
            val extraSelect = (if ("address" in columns) ", address" else ", NULL") +
                (if (hasSearchText) ", search_text" else ", NULL")
            val extraSelectFts = (if ("address" in columns) ", p.address" else ", NULL") +
                (if (hasSearchText) ", p.search_text" else ", NULL")
            fun add(cursor: android.database.Cursor) {
                val r = SearchResult(
                    cursor.getString(0), cursor.getString(1), cursor.getDouble(2), cursor.getDouble(3),
                    address = cursor.getString(4)?.takeIf { it.isNotBlank() },
                    searchText = cursor.getString(5),
                )
                results.putIfAbsent("${r.name}:${r.latitude}:${r.longitude}", r)
            }

            val ftsSupported = Fts5SupportDetector.isSupported(db)
            // 略称・英字の別名 (スタバ → スターバックス など) も同じ手順で探す
            for ((variantIndex, variant) in SearchAliases.expand(trimmed).withIndex()) {
                val variantLimit = if (variantIndex == 0) limit else limit / 2
                var ftsCount = 0
                var ftsSucceeded = false
                if (ftsSupported) {
                    try {
                        db.searchQuery(
                            """
                            SELECT p.name, p.category, p.lat, p.lon $extraSelectFts
                            FROM places_fts f
                            JOIN places p ON p.id = f.rowid
                            WHERE places_fts MATCH ? $categoryWhere
                            $orderBy
                            LIMIT ?
                            """.trimIndent(),
                            (listOf(buildFtsQuery(variant)) + categoryArgs + orderArgs + listOf(variantLimit.toString())).toTypedArray(),
                        ).use { cursor -> while (cursor.moveToNext()) { add(cursor); ftsCount++ } }
                        ftsSucceeded = true
                    } catch (e: SQLiteException) {
                        // FTS5未対応デバイスまたはエラー → LIKE検索へフォールバック
                        Log.w("CycleMap", "FTS5 query failed, falling back to LIKE search: ${e.message}")
                    }
                }

                // FTS5未対応 or FTS5クエリ失敗 or 件数不足 → LIKE補完
                if (!ftsSucceeded || ftsCount < variantLimit) {
                    val remaining = if (ftsSucceeded) variantLimit - ftsCount else variantLimit
                    val targetColumn = if (hasSearchText) "search_text" else "name"
                    val likeQuery = if (hasSearchText) SearchNormalizer.normalize(variant) else variant
                    // FTS5非対応時は複数単語をANDで繋ぎ、各単語の中間一致 (%word%) で検索する。
                    val (textWhereClause, textArgs) = buildLikeTextConditions(likeQuery, targetColumn)
                    db.searchQuery(
                        """
                        SELECT name, category, lat, lon $extraSelect
                        FROM places
                        WHERE ($textWhereClause) $categoryWherePlain
                        $orderBy
                        LIMIT ?
                        """.trimIndent(),
                        (textArgs + categoryArgs + orderArgs + listOf(remaining.toString())).toTypedArray(),
                    ).use { cursor -> while (cursor.moveToNext()) add(cursor) }
                }

                fetchStrongMatches(db, variant, hasSearchText, extraSelect, categoryWherePlain, categoryArgs, ::add)
            }

            // 「〜駅」検索時、OSM上で「広島」のように駅名のみで登録されている鉄道駅を救済
            // DEF-09: カテゴリが未指定（ALL）または鉄道駅（STATION）のときのみ救済を実行する
            val stationBase = SearchAliases.expand(trimmed).firstNotNullOfOrNull { extractStationQuery(it) }
            if (stationBase != null && (category == SearchCategory.ALL || category == SearchCategory.STATION)) {
                val stationWherePlain = "AND (category LIKE 'railway:station%' OR category LIKE 'public_transport:station%')"
                val targetColumn = if (hasSearchText) "search_text" else "name"
                val stLikeQuery = if (hasSearchText) SearchNormalizer.normalize(stationBase) else stationBase
                val (stTextWhereClause, stTextArgs) = buildLikeTextConditions(stLikeQuery, targetColumn)
                db.searchQuery(
                    """
                    SELECT name, category, lat, lon $extraSelect
                    FROM places
                    WHERE ($stTextWhereClause) $stationWherePlain
                    $orderBy
                    LIMIT 10
                    """.trimIndent(),
                    (stTextArgs + orderArgs).toTypedArray(),
                ).use { cursor -> while (cursor.moveToNext()) add(cursor) }
                fetchStrongMatches(db, stationBase, hasSearchText, extraSelect, stationWherePlain, emptyList(), ::add)
            }

            // 住所・地名での検索 (「袋町5-7」「中区袋町」など)。住所が一致した地点の代表点を 1 件出す。
            if ("address" in columns && (category == SearchCategory.ALL)) {
                addressResult(db, trimmed)?.let { r -> results.putIfAbsent("address:${r.name}", r) }
            }
        } else if (categoryPrefixes.isNotEmpty()) {
            // クエリ未入力でカテゴリ選択中 → 近傍カテゴリ一覧を表示。
            // 遠方行を拾わないよう現在地bboxで事前絞りする (逆ジオコーディングと同型の BETWEEN 方式)。
            // 全県検索・位置不明時は bbox を使わない。名称検索 (上2分岐) にも bbox を適用しない。
            val bboxArgs = if (restrictCategoryToOrigin && userLat != null && userLon != null) {
                nearbyBboxArgs(userLat, userLon, NEARBY_CATEGORY_BBOX_HALF_M)
            } else {
                emptyList()
            }
            val bboxWhere = if (bboxArgs.isNotEmpty()) "AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?" else ""
            // LIMIT前にSQL側で近似距離順に並べ、真の最近傍の切り捨てを防ぐ (厳密ソートはKotlin側)。
            val orderArgs = if (userLat != null && userLon != null) {
                nearbyOrderArgs(userLat, userLon)
            } else {
                emptyList()
            }
            val orderBy = if (orderArgs.isNotEmpty()) NEARBY_ORDER_BY else ""
            db.searchQuery(
                """
                SELECT name, category, lat, lon
                FROM places
                WHERE 1=1 $categoryWherePlain $bboxWhere
                $orderBy
                LIMIT ?
                """.trimIndent(),
                (categoryArgs + bboxArgs + orderArgs + listOf(limit.toString())).toTypedArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val r = SearchResult(cursor.getString(0), cursor.getString(1), cursor.getDouble(2), cursor.getDouble(3))
                    results["${r.name}:${r.latitude}:${r.longitude}"] = r
                }
            }
        }
    }

    // 距離計算
    val withDistance = results.values.map { r ->
        if (userLat != null && userLon != null) {
            val dist = haversineMeters(userLat, userLon, r.latitude, r.longitude)
            r.copy(distanceMeters = dist)
        } else {
            r
        }
    }

    // バス停クラスタリング（同名・300m以内を代表1件に集約）
    val clustered = clusterBusStops(withDistance)

    // クエリ一致度・カテゴリ優先度・距離によるランキング
    val ranked = rankSearchResults(clustered, query, userLat, userLon)
    return ranked.take(limit)
}
