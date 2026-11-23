package com.gorite.cyclemap.navigation

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.Future

private val prefetchExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "chain-prefetch").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}

// Accessed only by the single prefetch worker.
private val prefetchBuffer by lazy { ByteBuffer.allocateDirect(4 shl 20) }

/**
 * ファイルを先頭から順に読んで OS のページキャッシュに載せる (結果は捨てる)。
 * mmap 越しのランダムアクセス (CH の経路展開など) が、フラッシュの読み出し待ちで遅くなるのを避ける。
 * 失敗しても探索には影響しないので例外は握りつぶす。
 */
internal fun prefetchFilesInBackground(
    files: List<File>,
    isCancelled: () -> Boolean = { false },
): Future<*> = prefetchExecutor.submit {
    for (file in files) {
        if (isCancelled()) return@submit
        try {
            RandomAccessFile(file.path, "r").channel.use { ch ->
                val buffer = prefetchBuffer
                while (true) {
                    if (isCancelled()) return@submit
                    buffer.clear()
                    if (ch.read(buffer) < 0) break
                }
            }
        } catch (_: IOException) {
            // One unavailable file does not prevent prefetching the rest of the batch.
        } catch (_: SecurityException) {
            // Opening a file is best effort.
        }
    }
}

/** Tracks scheduled files across the legs and candidate retries of one plan. */
internal class RouteFilePrefetch(private val isCancelled: () -> Boolean) {
    private val scheduledPaths = HashSet<String>()

    fun enqueue(files: List<File>): Future<*>? {
        if (isCancelled()) return null
        val pending = files.filter { file ->
            val path = try {
                file.canonicalPath
            } catch (_: IOException) {
                file.absolutePath
            } catch (_: SecurityException) {
                file.absolutePath
            }
            scheduledPaths.add(path)
        }
        return if (pending.isEmpty()) null else prefetchFilesInBackground(pending, isCancelled)
    }
}
