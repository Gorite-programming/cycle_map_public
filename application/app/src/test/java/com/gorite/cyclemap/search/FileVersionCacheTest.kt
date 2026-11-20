package com.gorite.cyclemap.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileVersionCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun unchangedFileUsesCachedValue() {
        val file = folder.newFile("places.db").apply { writeText("old") }
        val cache = FileVersionCache<String>()
        assertEquals("old", cache.getOrLoad(file.path) { file.readText() })
        assertEquals("old", cache.getOrLoad(file.path) { error("must use cache") })
    }

    @Test fun changedLengthInvalidatesEvenWithSameTimestamp() {
        val file = folder.newFile("places.db").apply { writeText("old") }
        val modified = file.lastModified()
        val cache = FileVersionCache<Boolean>()
        assertEquals(false, cache.getOrLoad(file.path) { false })
        file.writeText("new schema with search_text")
        assertTrue(file.setLastModified(modified))
        assertEquals(true, cache.getOrLoad(file.path) { true })
    }

    @Test fun changedTimestampInvalidatesEvenWithSameLength() {
        val file = folder.newFile("places.db").apply { writeText("old") }
        val cache = FileVersionCache<Set<String>>()
        assertEquals(setOf("name"), cache.getOrLoad(file.path) { setOf("name") })
        val modified = file.lastModified()
        file.writeText("new")
        assertTrue(file.setLastModified(modified + 10_000))
        assertEquals(setOf("name", "search_text"), cache.getOrLoad(file.path) { setOf("name", "search_text") })
    }

    @Test fun pathsHaveIndependentEntriesAndClearReloads() {
        val first = folder.newFile("first.db")
        val second = folder.newFile("second.db")
        val cache = FileVersionCache<Int>()
        assertEquals(1, cache.getOrLoad(first.path) { 1 })
        assertEquals(2, cache.getOrLoad(second.path) { 2 })
        cache.clear()
        assertEquals(3, cache.getOrLoad(first.path) { 3 })
    }

    @Test fun nonFileDatabasesAreNotCached() {
        val cache = FileVersionCache<Int>()
        for (path in listOf(null, "", ":memory:", folder.root.resolve("missing.db").path)) {
            assertEquals(1, cache.getOrLoad(path) { 1 })
            assertEquals(2, cache.getOrLoad(path) { 2 })
        }
    }
}
