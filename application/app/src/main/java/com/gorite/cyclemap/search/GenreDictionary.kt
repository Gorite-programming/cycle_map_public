package com.gorite.cyclemap.search

data class GenreCategory(
    val id: String,
    val canonicalName: String,
    val synonyms: List<String>,
    val osmFilterCategories: List<String>
)

object GenreDictionary {

    val CATEGORIES = listOf(
        GenreCategory(
            id = "convenience",
            canonicalName = "コンビニ",
            synonyms = listOf("コンビニ", "こんびに", "convenience", "7-eleven", "セブン", "ローソン", "ファミマ"),
            osmFilterCategories = listOf("convenience")
        ),
        GenreCategory(
            id = "station",
            canonicalName = "駅",
            synonyms = listOf("駅", "えき", "station", "jr", "電停"),
            osmFilterCategories = listOf("station", "halt")
        ),
        GenreCategory(
            id = "rest_area",
            canonicalName = "道の駅",
            synonyms = listOf("道の駅", "みちのえき", "rest_area", "service_area", "sa", "pa"),
            osmFilterCategories = listOf("rest_area")
        ),
        GenreCategory(
            id = "toilet",
            canonicalName = "トイレ",
            synonyms = listOf("トイレ", "といれ", "お手洗い", "便所", "toilet", "wc"),
            osmFilterCategories = listOf("toilets")
        ),
        GenreCategory(
            id = "cafe_restaurant",
            canonicalName = "食事・カフェ",
            synonyms = listOf("食事", "カフェ", "かふぇ", "restaurant", "cafe", "喫茶", "うどん", "ラーメン"),
            osmFilterCategories = listOf("restaurant", "cafe", "fast_food")
        ),
        GenreCategory(
            id = "bicycle_parking",
            canonicalName = "駐輪場",
            synonyms = listOf("駐輪場", "ちゅうりんじょう", "自転車置場", "bicycle_parking"),
            osmFilterCategories = listOf("bicycle_parking")
        ),
        GenreCategory(
            id = "hospital",
            canonicalName = "病院・クリニック",
            synonyms = listOf("病院", "びょういん", "クリニック", "hospital", "clinic", "医院"),
            osmFilterCategories = listOf("hospital", "clinic", "doctors")
        )
    )

    /**
     * 種類そのものを表す語 → 検索 DB の category の接頭辞。
     * 「コンビニ」のように語全体が種類名のときは、名前の文字列では探さず、その種類の近い順の一覧にする。
     * 店名やブランド名 (「セブン」など) はここに入れない (他社の店まで出てしまうため)。
     * 「道の駅」は DB の category がそろっていないので入れない (名前の検索で見つかる)。
     */
    private val GENERIC_TERMS: Map<String, List<String>> = buildMap {
        fun put(prefixes: List<String>, vararg terms: String) = terms.forEach { put(it, prefixes) }
        put(listOf("shop:convenience"), "コンビニ", "コンビニエンスストア", "convenience")
        put(listOf("railway:station", "railway:halt"), "駅", "えき", "鉄道駅", "station")
        put(listOf("amenity:toilets"), "トイレ", "お手洗い", "便所", "公衆トイレ", "toilet", "toilets", "wc")
        put(listOf("amenity:cafe"), "カフェ", "喫茶", "喫茶店", "cafe")
        put(listOf("amenity:restaurant", "amenity:cafe", "amenity:fast_food"), "レストラン", "飲食店", "食事", "ごはん", "ご飯", "restaurant")
        put(listOf("amenity:fast_food"), "ファストフード", "ファーストフード")
        put(listOf("amenity:hospital", "amenity:clinic", "amenity:doctors"), "病院", "びょういん", "hospital")
        put(listOf("amenity:clinic", "amenity:doctors"), "クリニック", "医院", "診療所", "clinic")
        put(listOf("amenity:bicycle_parking"), "駐輪場", "ちゅうりんじょう", "自転車置場", "自転車置き場", "駐輪")
        put(listOf("shop:bicycle"), "自転車屋", "自転車店", "サイクルショップ")
        put(listOf("amenity:public_bath"), "温泉", "おんせん", "銭湯", "せんとう")
        put(listOf("shop:supermarket"), "スーパー", "スーパーマーケット", "supermarket")
    }.mapKeys { SearchNormalizer.normalize(it.key) }

    private val NEARBY_WORDS = listOf("近くの", "ちかくの", "付近の", "周辺の", "最寄りの", "最寄の")
        .map { SearchNormalizer.normalize(it) }

    /**
     * 語全体が種類名 (「コンビニ」「近くのトイレ」など) なら、その種類の category の接頭辞を返す。
     * 店名などを含む語 (「セブン」「広島駅」) は null (名前で探す)。
     */
    fun genericCategoryPrefixes(query: String): List<String>? {
        var normalized = SearchNormalizer.normalize(query).trim()
        NEARBY_WORDS.firstOrNull { normalized.startsWith(it) }?.let { normalized = normalized.removePrefix(it).trim() }
        if (normalized.isEmpty()) return null
        return GENERIC_TERMS[normalized]
    }

    /**
     * クエリ文字列（例: "近くのコンビニ", "広島駅", "トイレ"）からジャンルカテゴリを検出
     */
    fun matchGenre(query: String): GenreCategory? {
        val normalized = SearchNormalizer.normalize(query).replace("近くの", "").replace("ちかくの", "").trim()
        if (normalized.isEmpty()) return null

        for (cat in CATEGORIES) {
            for (syn in cat.synonyms) {
                val normSyn = SearchNormalizer.normalize(syn)
                if (normalized == normSyn || normalized.startsWith(normSyn) || normalized.endsWith(normSyn) || normalized.contains(normSyn)) {
                    return cat
                }
            }
        }
        return null
    }
}
