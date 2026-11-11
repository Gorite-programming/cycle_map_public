package com.gorite.cyclemap.routing

import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * 隣り合う 2 県の通り口 (共通 OSM ノード) を事前計算したファイル `<A>__<B>.portals`。
 * A, B は graph ファイルの基本名で、大文字小文字を無視した辞書順に固定する。逆向き (B→A) の探索では A/B の列を入れ替えて使う。
 *
 * 形式 (ビッグエンディアン):
 * - ヘッダ 68 バイト: マジック `CMPT` (Int)、バージョン (Int)、通り口の数 (Int)、
 *   A の graph の識別 (size, firstCrc, lastCrc の各 Long)、B の graph の識別 (同)、本体の CRC32 (Long)
 * - 本体: 通り口ごとに `nodeId (Long)`, `aIndex (Int)`, `bIndex (Int)`。`nodeId` の昇順 ([portalsBetween] と同じ並び)
 *
 * 読み込みは例外を投げない。無い・切れている・壊れている・識別が今の graph と合わないときは null を返し、
 * 呼び出し側は従来の ID 照合に戻る。
 */
object CrossPortalsFile {
    const val EXTENSION = ".portals"
    const val MAGIC = 0x434D5054 // "CMPT"
    const val VERSION = 1
    const val HEADER_BYTES = 4 + 4 + 4 + 24 + 24 + 8
    const val RECORD_BYTES = 8 + 4 + 4

    /** 2 つの graph ファイルの組に対するファイル名。引数の順序によらない。 */
    fun fileName(graphA: File, graphB: File): String {
        val (first, second) = listOf(graphA.nameWithoutExtension, graphB.nameWithoutExtension).sortedWith(NAME_ORDER)
        return "${first}__$second$EXTENSION"
    }

    /** [fileName] と同じ順序で [graphA] が先 (A 側) になるか。 */
    fun isCanonicalOrder(graphA: File, graphB: File): Boolean =
        NAME_ORDER.compare(graphA.nameWithoutExtension, graphB.nameWithoutExtension) <= 0

    private val NAME_ORDER = Comparator<String> { x, y -> x.compareTo(y, ignoreCase = true).let { if (it != 0) it else x.compareTo(y) } }

    /** [portals] の a 側が [a] の graph、b 側が [b] の graph。一時ファイルに書いてから置き換える。 */
    fun write(file: File, portals: CrossPortals, a: GraphFingerprint, b: GraphFingerprint) {
        require(portals.aIndices.size == portals.size && portals.bIndices.size == portals.size)
        val body = ByteBuffer.allocate(portals.size * RECORD_BYTES)
        for (p in 0 until portals.size) {
            require(p == 0 || portals.nodeIds[p - 1] < portals.nodeIds[p]) { "Portal node IDs must be strictly ascending" }
            body.putLong(portals.nodeIds[p]).putInt(portals.aIndices[p]).putInt(portals.bIndices[p])
        }
        val crc = CRC32().apply { update(body.array()) }.value
        file.absoluteFile.parentFile?.mkdirs()
        val partial = File(file.path + ".new")
        DataOutputStream(partial.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(portals.size)
            for (f in listOf(a, b)) { out.writeLong(f.size); out.writeLong(f.firstCrc); out.writeLong(f.lastCrc) }
            out.writeLong(crc)
            out.write(body.array())
        }
        if (!partial.renameTo(file)) { file.delete(); check(partial.renameTo(file)) { "Cannot replace ${file.absolutePath}" } }
    }

    /**
     * [from] → [to] の向きの通り口 (a 側が [from])。ファイルがどちら向きで保存されていても読める。使えなければ null。
     * 識別が不明 ([GraphFingerprint.UNKNOWN]) の graph には決して一致させない。
     */
    fun read(file: File, from: GraphFingerprint, to: GraphFingerprint): CrossPortals? = try {
        readOrNull(file, from, to)
    } catch (e: Exception) { null }

    private fun readOrNull(file: File, from: GraphFingerprint, to: GraphFingerprint): CrossPortals? {
        if (from.size < 0 || to.size < 0 || from == to || !file.isFile) return null
        val length = file.length()
        if (length < HEADER_BYTES || (length - HEADER_BYTES) % RECORD_BYTES != 0L || length > MAX_FILE_BYTES) return null
        val bytes = file.readBytes()
        if (bytes.size.toLong() != length) return null
        val buf = ByteBuffer.wrap(bytes)
        if (buf.int != MAGIC || buf.int != VERSION) return null
        val count = buf.int
        if (count < 0 || HEADER_BYTES + count.toLong() * RECORD_BYTES != length) return null
        val a = GraphFingerprint(buf.long, buf.long, buf.long)
        val b = GraphFingerprint(buf.long, buf.long, buf.long)
        val swapped = when {
            a == from && b == to -> false
            a == to && b == from -> true
            else -> return null
        }
        val crc = CRC32().apply { update(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES) }.value
        if (buf.long != crc) return null
        val ids = LongArray(count); val ai = IntArray(count); val bi = IntArray(count)
        for (p in 0 until count) {
            ids[p] = buf.long; ai[p] = buf.int; bi[p] = buf.int
            if (ai[p] < 0 || bi[p] < 0 || (p > 0 && ids[p - 1] >= ids[p])) return null
        }
        return if (swapped) CrossPortals(bi, ai, ids) else CrossPortals(ai, bi, ids)
    }

    /** 通り口は多くても数万件。それを大きく超えるファイルは壊れているとみなしてヒープに読まない。 */
    private const val MAX_FILE_BYTES = 64L shl 20
}

/**
 * [fromGraph] → [toGraph] の通り口ファイルを [dir] から探して読む (ファイル名の大文字小文字は問わない)。
 * 無い・古い (graph を作り直した)・壊れているときは null。例外は投げない。
 * [fingerprint] を渡すと、同じ graph の識別の計算 (先頭/末尾 1MB の読み込み) を呼び出し側で使い回せる。
 */
fun loadCrossPortals(
    fromGraph: File,
    toGraph: File,
    dir: File? = fromGraph.absoluteFile.parentFile,
    fingerprint: (File) -> GraphFingerprint = GraphFingerprint::fromFile,
): CrossPortals? = try {
    val name = CrossPortalsFile.fileName(fromGraph, toGraph)
    val file = dir?.let { d -> File(d, name).takeIf { it.isFile } ?: d.listFiles { f -> f.isFile && f.name.equals(name, ignoreCase = true) }?.firstOrNull() }
    file?.let { CrossPortalsFile.read(it, fingerprint(fromGraph), fingerprint(toGraph)) }
} catch (e: Exception) { null }
