package com.gorite.cyclemap.tracking

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalRestoreOrderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun newestJournalComesFirstRegardlessOfName() {
        val older = folder.newFile("ffff.journal").apply { setLastModified(1_000_000L) }
        val newer = folder.newFile("0000.journal").apply { setLastModified(2_000_000L) }
        val other = folder.newFile("note.txt").apply { setLastModified(3_000_000L) }
        assertEquals(listOf(newer, older), journalsInRestoreOrder(listOf(older, other, newer)))
    }
}
