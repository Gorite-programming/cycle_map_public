package com.gorite.cyclemap.navigation

import com.gorite.cyclemap.data.PrefectureData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefectureAdjacencyTest {
    private val edges = PREFECTURE_ADJACENCY_EDGES

    @Test fun coversAllPrefecturesWithoutSelfLoopsOrDuplicates() {
        val expected = PrefectureData.ALL.map { it.name }.toSet()
        assertEquals(expected, PREFECTURE_ADJACENCY.keys)
        assertTrue(edges.all { (a, b) -> a != b })
        assertEquals(edges.size, edges.map { canonical(it.first, it.second) }.toSet().size)
    }

    @Test fun everyNameExistsInPrefectureData() {
        val names = PrefectureData.ALL.map { it.name }.toSet()
        assertTrue(PREFECTURE_ADJACENCY.all { (prefecture, neighbors) ->
            prefecture in names && neighbors.all { it in names }
        })
    }

    @Test fun mainlandAndShikokuRoadNetworkIsOneConnectedComponent() {
        val expected = PrefectureData.ALL.map { it.name }.toSet() - setOf("北海道", "沖縄県")
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        edges.forEach { (a, b) ->
            adjacency.getOrPut(a) { mutableSetOf() }.add(b)
            adjacency.getOrPut(b) { mutableSetOf() }.add(a)
        }
        val reached = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(expected.first()))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (reached.add(current)) queue.addAll(adjacency[current].orEmpty() - reached)
        }
        assertEquals(expected, reached)
    }

    @Test fun includesSpecifiedRoadLinksAndExcludesDisconnectedIslands() {
        listOf(
            "山口県" to "福岡県", "広島県" to "愛媛県", "岡山県" to "香川県",
            "兵庫県" to "徳島県", "千葉県" to "神奈川県", "愛知県" to "岐阜県",
            "福岡県" to "大分県",
        ).forEach { (a, b) -> assertTrue("missing $a-$b", contains(a, b)) }
        assertFalse(contains("北海道", "青森県"))
        val shikoku = setOf("徳島県", "香川県", "愛媛県", "高知県")
        val kyushu = setOf("福岡県", "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県")
        assertFalse(edges.any { (a, b) -> a in shikoku && b in kyushu || a in kyushu && b in shikoku })
        assertFalse(contains("兵庫県", "香川県"))
        assertFalse(edges.any { "沖縄県" in it.toList() })
    }

    @Test fun plansExpectedAvailablePrefectureChains() {
        val crossHonshu = setOf("山口県", "広島県", "岡山県", "兵庫県")
        assertEquals(
            listOf(listOf("山口県", "広島県", "岡山県", "兵庫県")),
            planPrefectureChains("山口県", "兵庫県", crossHonshu, emptyMap()),
        )
        val shimanami = setOf("広島県", "愛媛県")
        assertEquals(
            listOf(listOf("広島県", "愛媛県")),
            planPrefectureChains("広島県", "愛媛県", shimanami, emptyMap()),
        )
    }

    private fun contains(a: String, b: String): Boolean = edges.any { canonical(it.first, it.second) == canonical(a, b) }

    private fun canonical(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a
}
