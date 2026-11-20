package com.gorite.cyclemap.ui.components

import com.gorite.cyclemap.search.SearchCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DestinationSearchRequestTest {
    private val completed = DestinationSearchRequest("広島駅", SearchCategory.ALL, 34.0 to 132.0, listOf("hiroshima.search.db"))

    @Test
    fun invalidatesCandidatesImmediatelyWhenInputOrSearchScopeChanges() {
        assertNotEquals(completed, completed.copy(query = "広島港"))
        assertNotEquals(completed, completed.copy(category = SearchCategory.STATION))
        assertNotEquals(completed, completed.copy(origin = 35.0 to 133.0))
        assertNotEquals(completed, completed.copy(databasePaths = listOf("okayama.search.db")))
        assertNotEquals(completed, null)
    }

    @Test
    fun enablesCandidatesForTheirCompletedRequestOnly() {
        val current = completed.copy(query = "広島港")
        assertNotEquals(current, completed)
        val newlyCompleted = current.copy()
        assertEquals(current, newlyCompleted)
    }

    @Test
    fun returningToPreviousInputWaitsForItsNewResults() {
        val returned = DestinationSearchRequest(completed.query, completed.category, completed.origin, completed.databasePaths)
        assertNotEquals(completed, returned)
    }

    @Test
    fun smallLocationJitterDoesNotChangeTheCoarseOrigin() {
        assertEquals(coarseSearchOrigin(34.39601 to 132.45902), coarseSearchOrigin(34.39640 to 132.45871))
        assertNotEquals(coarseSearchOrigin(34.396 to 132.459), coarseSearchOrigin(34.398 to 132.459))
        assertEquals(null, coarseSearchOrigin(null))
    }
}
