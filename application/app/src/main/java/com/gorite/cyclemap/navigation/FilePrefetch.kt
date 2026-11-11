package com.gorite.cyclemap.navigation

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * ファイルを先頭から順に読んで OS のページキャッシュに載せる (結果は捨てる)。
 * mmap 越しのランダムアクセス (CH の経路展開など) が、フラッシュの読み出し待ちで遅くなるのを避ける。
 * 失敗しても探索には影響しないので例外は握りつぶす。
 */
fun prefetchFilesInBackground(files: List<File>, name: String = "chain-prefetch"): Thread = Thread({
    val buffer = ByteBuffer.allocateDirect(4 shl 20)
    for (file in files) {
        try {
            RandomAccessFile(file, "r").channel.use { ch ->
                buffer.clear()
                while (ch.read(buffer) >= 0) buffer.clear()
            }
        } catch (_: Throwable) { return@Thread }
    }
}, name).apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
