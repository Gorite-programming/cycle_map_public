package com.gorite.cyclemap.importer

/**
 * 案内に出す道路名を OSM のタグから決める。Google マップの案内と同じく、番号のある国道・県道は
 * 「国道2号」「県道41号」とし、番号の無い道は名前 (name) を使う。どちらも無ければ null。
 * - 国道: network=JP:national、または highway=trunk(_link) で ref がある
 * - 県道: network=JP:prefectural(:…)、または highway=primary/secondary(_link) で ref がある
 */
fun roadDisplayName(tags: Map<String, String>): String? {
    val name = tags["name"]?.trim()?.takeIf { it.isNotEmpty() }
    val ref = tags["ref"]?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() } }
    val highway = tags["highway"]?.removeSuffix("_link")
    val network = tags["network"]
    return when {
        ref != null && (network == "JP:national" || (network == null && highway == "trunk")) -> "国道${ref}号"
        ref != null && (network?.startsWith("JP:prefectural") == true ||
            (network == null && (highway == "primary" || highway == "secondary"))) -> "県道${ref}号"
        else -> name
    }
}

/**
 * way の進行方向ごとの車線 (OSM の turn:lanes)。戻り値は (way の向き, 逆向き)。
 * - turn:lanes:forward / turn:lanes:backward があればそれぞれ
 * - 一方通行の道は turn:lanes をその向きに (oneway=-1 は逆向き)
 * - 両方向の道の、向きの付かない turn:lanes は、どちら向きか決められないので使わない
 */
fun turnLanesByDirection(tags: Map<String, String>): Pair<String?, String?> {
    fun clean(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 }
    val oneway = tags["oneway"]
    val plain = clean(tags["turn:lanes"])
    val forward = clean(tags["turn:lanes:forward"]) ?: if (oneway in setOf("yes", "true", "1")) plain else null
    val backward = clean(tags["turn:lanes:backward"]) ?: if (oneway == "-1") plain else null
    return forward to backward
}

private val CODE_LIKE_CHAR_TYPES = setOf(
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    Character.MATH_SYMBOL.toInt(),
    Character.CURRENCY_SYMBOL.toInt(),
    Character.MODIFIER_SYMBOL.toInt(),
    Character.OTHER_SYMBOL.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
)

private fun isCodeLikeJunctionName(s: String): Boolean = s.all { ch ->
    ch.code < 128 ||
        ch in '０'..'９' || ch in 'Ａ'..'Ｚ' || ch in 'ａ'..'ｚ' ||
        ch.isWhitespace() ||
        Character.getType(ch) in CODE_LIKE_CHAR_TYPES
}

/**
 * 案内に出す交差点名を OSM のノードタグから整える。
 * - 前後の空白を除き、`;` で区切られていれば最初の 1 つだけを使う
 * - 末尾の「交差点」を除く
 * - 空、数字・英字・記号だけ (管理番号らしいもの)、25 文字超は null
 */
fun junctionDisplayName(tags: Map<String, String>): String? {
    val raw = tags["name"]?.split(';')?.firstOrNull()?.trim() ?: return null
    val cleaned = raw.removeSuffix("交差点").trim()
    if (cleaned.isEmpty() || cleaned.length > 25) return null
    if (isCodeLikeJunctionName(cleaned)) return null
    return cleaned
}

/**
 * 交差点名の読み (ひらがな優先、`name:ja-Hira` が無ければ `name:ja_kana`)。無ければ null。
 */
fun junctionReading(tags: Map<String, String>): String? {
    // 名前 (25 文字まで) の読みとして長すぎるものは壊れたタグとみなす (writeUTF の上限にも当たらないようにする)
    fun clean(v: String?) = v?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }
    return clean(tags["name:ja-Hira"]) ?: clean(tags["name:ja_kana"])
}
