package com.gorite.cyclemap.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoutingGraphHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun graph(name: String) {
        File(folder.root, name).writeText("graph")
        File(folder.root, "$name.idx").writeText("index")
    }

    @Test fun startupPrefersLastUsedGraphOverAlphabeticalFirst() {
        graph("Aichi.graph")
        graph("Yamaguchi.graph")
        assertEquals("Yamaguchi.graph", RoutingGraphHistory.findStartupGraph(folder.root, "Yamaguchi.graph")!!.first.name)
    }

    @Test fun missingLastUsedGraphOrIndexFallsBackToFirstAvailable() {
        graph("Aichi.graph")
        File(folder.root, "Yamaguchi.graph").writeText("graph")
        assertEquals("Aichi.graph", RoutingGraphHistory.findStartupGraph(folder.root, "Yamaguchi.graph")!!.first.name)
        assertEquals("Aichi.graph", RoutingGraphHistory.findStartupGraph(folder.root, null)!!.first.name)
    }
}
