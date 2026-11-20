package com.gorite.cyclemap.search

import java.io.File

/** ファイル差し替え時に再読込し、同じパスの古い世代は保持しない。 */
internal class FileVersionCache<T> {
    private data class Key(val path: String, val lastModified: Long, val length: Long)

    private val values = HashMap<Key, T>()

    @Synchronized
    fun getOrLoad(path: String?, load: () -> T): T {
        // インメモリ DB や存在しないファイルは、他の DB と判定を共有しない。
        if (path.isNullOrEmpty()) return load()
        val file = File(path)
        if (!file.isFile) return load()
        val key = Key(path, file.lastModified(), file.length())
        values.keys.removeAll { it.path == path && it != key }
        return values.getOrPut(key, load)
    }

    @Synchronized
    fun clear() = values.clear()
}
