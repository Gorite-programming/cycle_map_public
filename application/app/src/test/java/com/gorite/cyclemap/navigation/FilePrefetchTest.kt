package com.gorite.cyclemap.navigation

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilePrefetchTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a plan reads a file once across requests`() {
        val opens = AtomicInteger()
        val backingFile = temp.newFile()
        val file = observedFile(backingFile) { opens.incrementAndGet() }
        val plan = RouteFilePrefetch { false }

        val first = plan.enqueue(listOf(file, backingFile))
        val other = plan.enqueue(listOf(file, temp.newFile()))
        assertNull(plan.enqueue(listOf(File(backingFile.parentFile, "./${backingFile.name}"))))
        first!!.get(5, SECONDS)
        other!!.get(5, SECONDS)
        assertNull(plan.enqueue(listOf(backingFile)))
        assertEquals(1, opens.get())

        RouteFilePrefetch { false }.enqueue(listOf(file))!!.get(5, SECONDS)
        assertEquals(2, opens.get())
    }

    @Test
    fun `cancellation stops between chunks and skips the remaining files`() {
        val file = temp.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(16L shl 20) }
        val secondOpened = AtomicBoolean()
        val second = observedFile(temp.newFile()) { secondOpened.set(true) }
        val opened = AtomicBoolean()
        val chunkChecks = AtomicInteger()
        val cancel = AtomicBoolean()

        val plan = RouteFilePrefetch {
            if (opened.get() && chunkChecks.incrementAndGet() == 2) cancel.set(true)
            cancel.get()
        }
        plan.enqueue(listOf(observedFile(file) { opened.set(true) }, second))!!.get(5, SECONDS)

        assertTrue(cancel.get())
        assertEquals(2, chunkChecks.get())
        assertFalse(secondOpened.get())
    }

    @Test
    fun `separate prefetch requests never read concurrently`() {
        val firstOpened = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondOpened = CountDownLatch(1)
        val first = observedFile(temp.newFile()) {
            firstOpened.countDown()
            releaseFirst.await(5, SECONDS)
        }
        val second = observedFile(temp.newFile()) { secondOpened.countDown() }

        prefetchFilesInBackground(listOf(first))
        try {
            assertTrue(firstOpened.await(5, SECONDS))
            prefetchFilesInBackground(listOf(second))
            assertFalse(secondOpened.await(1, SECONDS))
        } finally {
            releaseFirst.countDown()
        }
        assertTrue(secondOpened.await(5, SECONDS))
    }

    private fun observedFile(file: File, onOpen: () -> Unit): File = object : File(file.path) {
        override fun getCanonicalPath(): String = file.canonicalPath

        override fun getPath(): String {
            onOpen()
            return super.getPath()
        }
    }
}
