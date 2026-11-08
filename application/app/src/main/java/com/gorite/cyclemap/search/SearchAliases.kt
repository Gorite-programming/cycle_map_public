package com.gorite.cyclemap.search

/**
 * 検索語の別名展開: 略称・英字・よくある言い換えを、検索DBにある正式な表記にも広げる。
 * 例: 「すたば」「スタバ」「starbucks」→ スターバックス、「マック」「マクド」→ マクドナルド、
 * 「hiroshima station」→ hiroshima駅。
 * 展開は検索語全体が別名に一致するとき (と、英語の「〜 station」) だけ行い、部分一致では広げない
 * (「せぶん」で「セブンスター」まで拾わないため)。
 */
internal object SearchAliases {
    private val ALIASES: Map<String, List<String>> = buildMap {
        fun add(canonical: String, vararg keys: String) = keys.forEach { put(key(it), (get(key(it)) ?: emptyList()) + canonical) }
        add("スターバックス", "すたば", "スタバ", "starbucks", "starbucks coffee", "sutaba")
        add("マクドナルド", "まっく", "マック", "まくど", "マクド", "mcdonalds", "mcdonald's", "mcdonald", "makku")
        add("ケンタッキー", "けんた", "ケンタ", "kfc", "kentucky")
        add("ミスタードーナツ", "みすど", "ミスド", "mister donut", "misdo")
        add("セブン-イレブン", "せぶいれ", "セブイレ", "せぶんいれぶん", "seven eleven", "7-eleven", "7eleven", "711", "7-11")
        add("ファミリーマート", "ふぁみま", "ファミマ", "familymart", "family mart", "famima")
        add("ローソン", "lawson")
        add("ミニストップ", "ministop")
        add("コメダ珈琲", "こめだ", "コメダ", "komeda")
        add("サイゼリヤ", "さいぜ", "サイゼ", "saizeriya")
        add("ドン・キホーテ", "どんき", "ドンキ", "donki", "don quijote")
        add("イオン", "aeon")
        add("ゆめタウン", "youme town", "youmetown", "yumetown")
        add("ドトール", "doutor")
        add("タリーズ", "tullys", "tully's")
        add("モスバーガー", "もす", "モス", "mos burger")
        add("吉野家", "yoshinoya")
        add("すき家", "sukiya")
        add("サイクルベースあさひ", "cycle base asahi")
        add("ダイソー", "daiso")
        add("ユニクロ", "uniqlo")
        add("道の駅", "michinoeki", "roadside station")
        add("郵便局", "post office", "ゆうびんきょく")
    }

    /** 比較用のキー: 正規化 (かな・全角・大小) し、空白・中黒・ハイフン・アポストロフィを除く。 */
    private fun key(s: String): String = SearchNormalizer.normalize(s).filterNot { it.isWhitespace() || it in "・-‐'’." }

    /** 元の検索語を先頭に、別名を展開した検索語の一覧 (重複なし、最大 4 件)。 */
    fun expand(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = linkedSetOf(q)
        ALIASES[key(q)]?.let { out += it }
        // 英語の「〜 station」は「〜駅」にする (駅名の救済検索に乗せる)
        Regex("^(.+?)\\s*station$", RegexOption.IGNORE_CASE).matchEntire(q)?.let { out += it.groupValues[1].trim() + "駅" }
        return out.take(4)
    }
}
