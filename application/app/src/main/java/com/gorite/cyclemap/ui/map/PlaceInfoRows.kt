package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.contract.PlaceDetail

/** 場所の詳細に並べる情報の種類。 */
internal enum class PlaceInfoKind { HOURS, PHONE, WEBSITE, WHEELCHAIR }

/** 場所の詳細の 1 行。[uri] があれば押したときに開く (tel: / http(s):)。 */
internal data class PlaceInfoRow(val kind: PlaceInfoKind, val text: String, val uri: String? = null)

/** 検索DB の列 (営業時間・電話・Web・車椅子) から、場所の詳細に並べる行を作る。空の項目は出さない。 */
internal fun placeInfoRows(detail: PlaceDetail): List<PlaceInfoRow> = buildList {
    detail.openingHoursText?.trim()?.takeIf { it.isNotEmpty() }?.let {
        add(PlaceInfoRow(PlaceInfoKind.HOURS, humanizeOpeningHours(it)))
    }
    detail.phone?.let(::splitValues)?.takeIf { it.isNotEmpty() }?.let { phones ->
        add(PlaceInfoRow(PlaceInfoKind.PHONE, phones.joinToString(" / "), telUri(phones.first())))
    }
    detail.website?.let(::splitValues)?.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }?.let { url ->
        add(PlaceInfoRow(PlaceInfoKind.WEBSITE, websiteLabel(url), url))
    }
    wheelchairLabel(detail.wheelchair)?.let { add(PlaceInfoRow(PlaceInfoKind.WHEELCHAIR, it)) }
}

/** OSM の複数値 (`;` 区切り) を分ける。 */
private fun splitValues(raw: String): List<String> = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }

/** ダイヤル画面に渡す tel: URI。数字と先頭の + 以外は除く。番号が無ければ null。 */
internal fun telUri(phone: String): String? {
    val digits = phone.trim().let { p -> (if (p.startsWith("+")) "+" else "") + p.filter { it.isDigit() } }
    return if (digits.count { it.isDigit() } >= 3) "tel:$digits" else null
}

/** 画面に出す Web の表記。スキームと先頭の www.、末尾の / を除く。長すぎるものは省略する。 */
internal fun websiteLabel(url: String): String {
    val s = url.substringAfter("://").removePrefix("www.").trimEnd('/')
    return if (s.length > 40) s.take(39) + "…" else s
}

/** OSM の wheelchair の値を日本語にする。分からない値は null (表示しない)。 */
internal fun wheelchairLabel(value: String?): String? = when (value?.trim()?.lowercase()) {
    "yes", "designated" -> "車椅子で利用可"
    "limited" -> "車椅子は一部利用可"
    "no" -> "車椅子は利用不可"
    else -> null
}

private val DAY_NAMES = mapOf(
    "Mo" to "月", "Tu" to "火", "We" to "水", "Th" to "木", "Fr" to "金", "Sa" to "土", "Su" to "日",
    "PH" to "祝日", "SH" to "学校休暇",
)

/**
 * OSM の opening_hours を読みやすくする (表示用。判定は OpeningHoursParser が行う)。
 * 曜日・祝日・off を日本語にし、規則の区切り `;` を ` / ` にする。`24/7` は「24時間営業」。
 */
internal fun humanizeOpeningHours(raw: String): String {
    val text = raw.trim()
    if (text == "24/7") return "24時間営業"
    return text.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ") { rule ->
        var r = rule.replace("24/7", "24時間営業")
        r = Regex("\\b(Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)\\b").replace(r) { DAY_NAMES.getValue(it.value) }
        r = Regex("\\b(off|closed)\\b", RegexOption.IGNORE_CASE).replace(r, "休み")
        r
    }
}
