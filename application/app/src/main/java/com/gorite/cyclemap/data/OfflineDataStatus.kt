package com.gorite.cyclemap.data

import java.io.File

/** 1 県分の端末内データの有無と、探索で見つかったファイルの実容量。 */
data class PrefectureDataStatus(
    val id: String,
    val name: String,
    val hasRouting: Boolean,
    val hasSearch: Boolean,
    val hasVectorMap: Boolean,
    val hasTerrain: Boolean = false,
    val hasFastRouting: Boolean = false,
    val totalBytes: Long = 0L,
) {
    val isComplete: Boolean get() = hasRouting && hasSearch && hasVectorMap
    val isEmpty: Boolean get() = !hasRouting && !hasSearch && !hasVectorMap && !hasTerrain && !hasFastRouting
}

/** 配置済み県を定義順で返す。呼び出し側で IO dispatcher に切り替える。 */
internal fun offlineDataStatus(dataDir: File): List<PrefectureDataStatus> =
    PrefectureData.ALL.map { prefectureDataStatus(dataDir, it.id, it.name) }.filterNot { it.isEmpty }
