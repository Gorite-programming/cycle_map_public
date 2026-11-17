package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.data.Prefecture
import com.gorite.cyclemap.data.PrefectureData
import com.gorite.cyclemap.data.PrefectureDataStatus

/**
 * 47 都道府県の地方区分と絞り込み・検索・現在地優先並べ替えロジック。
 * 純粋 Kotlin のため JVM ユニットテストで直接検証できる。
 */
internal object PrefectureFilter {
    val REGIONS: List<String> = PrefectureData.REGIONS

    /** 都道府県 ID -> 地方名の対応表 (全 47 都道府県)。 */
    val REGION_BY_PREFECTURE_ID: Map<String, String> = PrefectureData.ALL.associate { it.id to it.region }

    /** 都道府県名 -> 地方名の対応表 (全 47 都道府県)。 */
    val REGION_BY_PREFECTURE_NAME: Map<String, String> = PrefectureData.ALL.associate { it.name to it.region }

    /**
     * 地方名の表記ゆれ (「九州沖縄」↔「九州・沖縄」など) を正規化する。
     * 「すべて」または空白は絞り込みなし (null) として扱う。
     */
    fun normalizeRegion(region: String?): String? {
        val trimmed = region?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed == "すべて") return null
        return when (trimmed) {
            "九州沖縄", "九州・沖縄" -> "九州・沖縄"
            else -> trimmed
        }
    }

    /**
     * 地方と県名・ローマ字検索で絞り込み、現在地の県が一致すれば先頭に並べる。
     */
    fun filterAndSort(
        prefectures: List<Prefecture> = PrefectureData.ALL,
        region: String? = null,
        query: String = "",
        currentPrefectureName: String? = null,
    ): List<Prefecture> {
        val normalizedRegion = normalizeRegion(region)
        val trimmedQuery = query.trim()
        val currentTarget = currentPrefectureName?.trim()?.takeIf { it.isNotEmpty() }

        val filtered = prefectures.filter { pref ->
            val regionOk = normalizedRegion == null || pref.region == normalizedRegion
            val queryOk = trimmedQuery.isEmpty() ||
                pref.name.contains(trimmedQuery, ignoreCase = true) ||
                pref.id.contains(trimmedQuery, ignoreCase = true)
            regionOk && queryOk
        }

        if (currentTarget == null) return filtered
        return filtered.sortedBy { pref ->
            if (pref.name == currentTarget || pref.id.equals(currentTarget, ignoreCase = true)) 0 else 1
        }
    }

    /**
     * 「端末のデータ」タブでも現在地の県が入っていれば先頭に並べる。
     */
    fun sortInstalledFirst(
        statuses: List<PrefectureDataStatus>,
        currentPrefectureName: String?,
    ): List<PrefectureDataStatus> {
        val currentTarget = currentPrefectureName?.trim()?.takeIf { it.isNotEmpty() } ?: return statuses
        return statuses.sortedBy { status ->
            if (status.name == currentTarget || status.id.equals(currentTarget, ignoreCase = true)) 0 else 1
        }
    }
}
