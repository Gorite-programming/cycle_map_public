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
 * 置き換える前に、大きさ・空き容量・ファイルの先頭の形式を確かめる。配布元の正しさ (署名・ハッシュ) の
 * 確認はしていない (アプリ内ダウンロードを作るときに、マニフェストの照合を足す)。
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

    /** 1 ファイルの上限。県のグラフは 2 GiB 未満 (読み込み側の制約)、タイルも数百 MB なので、これを超えるものは壊れているか別物。 */
    private const val MAX_FILE_BYTES = 4L shl 30

    /** 書き込み中も残しておく空き容量。使い切ると、端末もアプリ (記録の保存) も動かなくなる。 */
    private const val MIN_FREE_BYTES = 64L shl 20

    /**
     * ファイルの先頭が、その種類の形式になっているか。拡張子だけ合った別物 (壊れたダウンロード、
     * HTML のエラーページ、名前を変えただけのファイル) を、今あるデータと置き換える前に弾く。
     */
    internal fun looksLikeDataFile(name: String, head: ByteArray): Boolean {
        fun startsWith(magic: String) = head.size >= magic.length && String(head, 0, magic.length, Charsets.ISO_8859_1) == magic
        fun contains(magic: String) = String(head, Charsets.ISO_8859_1).contains(magic)
        return when {
            name.endsWith(".graph.idx") -> contains("CYCLEMAP_INDEX_V")
            name.endsWith(".graph.ch") -> startsWith("CHJ1")
            name.endsWith(".graph") -> contains("CYCLEMAP_GRAPH_V")
            name.endsWith(".search.db") -> startsWith("SQLite format 3")
            name.endsWith(".pmtiles") -> startsWith("PMTiles")
            name.endsWith(".portals") -> startsWith("CMPT")
            else -> false
        }
    }

    private fun writeAtomically(input: InputStream, target: File) {
        val dir = target.parentFile ?: error("置き場所がありません")
        val part = File(dir, target.name + ".part")
        try {
            val buffer = ByteArray(1 shl 20)
            var total = 0L
            var head = ByteArray(0)
            part.outputStream().use { out ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (head.size < 64) head += buffer.copyOf(minOf(n, 64 - head.size))
                    total += n
                    if (total > MAX_FILE_BYTES) error("ファイルが大きすぎます: ${target.name}")
                    if (dir.usableSpace < MIN_FREE_BYTES) error("空き容量が足りません: ${target.name}")
                    out.write(buffer, 0, n)
                }
            }
            if (!looksLikeDataFile(target.name, head)) error("中身が ${target.name} の形式ではありません")
            if (target.exists() && !target.delete()) error("既存のファイルを置き換えられません: ${target.name}")
            if (!part.renameTo(target)) error("ファイルを置けません: ${target.name}")
        } finally {
            // 失敗・中断のときに、書きかけを残さない (成功時は renameTo で消えている)
            part.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
