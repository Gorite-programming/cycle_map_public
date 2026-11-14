package com.gorite.cyclemap.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 端末に保存したデータのファイル (県の ZIP、または .graph などの単体) をアプリのデータフォルダへ取り込む。
 *
 * PC (adb) が無くても、スマホのブラウザで落としたファイルから経路・検索・地図のデータを入れられるようにする。
 * 取り込むのは名前の形が分かっているファイルだけ (経路・検索・タイル・地形・ポータル)。ほかは読み飛ばす。
 * 書き込みは一時ファイルに書いてから置き換えるので、途中で失敗しても古いファイルは壊れない。
 */
object OfflineDataImporter {

    private val SUFFIXES = listOf(
        ".graph.idx", ".graph.ch", ".graph", ".search.db",
        ".contours.pmtiles", ".hillshade.pmtiles", ".pmtiles", ".portals",
    )

    // ブラウザが同名のファイルを落とし直したときに付ける「 (1)」「-1」
    private val BROWSER_COPY_SUFFIX = Regex("""(?: \(\d+\)|-\d+)$""")

    /**
     * 取り込み先のファイル名。取り込まないファイル (manifest.json・LICENSE.txt・知らない拡張子) は null。
     * 例: "Hiroshima (1).graph" → "Hiroshima.graph"、"x/Hiroshima.search.db" → "Hiroshima.search.db"。
     */
    fun targetName(rawName: String): String? {
        val name = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val suffix = SUFFIXES.firstOrNull { name.endsWith(it, ignoreCase = true) } ?: return null
        val stem = name.dropLast(suffix.length).replace(BROWSER_COPY_SUFFIX, "")
        // 県名 (と「A__B」のポータル) だけを許す。パスの区切りや記号を含む名前は取り込まない
        if (stem.isEmpty() || !stem.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }) return null
        return stem + suffix.lowercase()
    }

    data class Result(val imported: List<String>, val skipped: List<String>)

    /** [uris] のファイルを [dataDir] に取り込む。ZIP は中身を展開する。IO スレッドで呼ぶこと。 */
    fun import(context: Context, uris: List<Uri>, dataDir: File, onProgress: (String) -> Unit = {}): Result {
        dataDir.mkdirs()
        val imported = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (uri in uris) {
            val displayName = displayName(context, uri) ?: uri.lastPathSegment ?: "?"
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                skipped += displayName
                continue
            }
            input.use { stream ->
                if (displayName.endsWith(".zip", ignoreCase = true)) {
                    ZipInputStream(stream.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val target = targetName(entry.name)
                            if (target == null) {
                                skipped += entry.name
                            } else {
                                onProgress(target)
                                writeAtomically(zip, File(dataDir, target))
                                imported += target
                            }
                        }
                    }
                } else {
                    val target = targetName(displayName)
                    if (target == null) {
                        skipped += displayName
                    } else {
                        onProgress(target)
                        writeAtomically(stream, File(dataDir, target))
                        imported += target
                    }
                }
            }
        }
        return Result(imported, skipped)
    }

    private fun writeAtomically(input: InputStream, target: File) {
        val part = File(target.parentFile, target.name + ".part")
        part.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
        if (target.exists() && !target.delete()) error("既存のファイルを置き換えられません: ${target.name}")
        if (!part.renameTo(target)) error("ファイルを置けません: ${target.name}")
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
