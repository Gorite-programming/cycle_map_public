package com.gorite.cyclemap.ui.map

import com.gorite.cyclemap.contract.LaneInfo
import com.gorite.cyclemap.contract.TurnType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaneGuidanceVisibilityTest {
    private val lanes = listOf(
        LaneInfo(TurnType.Straight, isRecommended = false),
        LaneInfo(TurnType.Right, isRecommended = true),
    )

    @Test
    fun showsLaneHintsDuringGuidance() {
        assertTrue(shouldShowLaneGuidance(lanes, isRerouting = false))
    }

    @Test
    fun hidesEmptyLaneHints() {
        assertFalse(shouldShowLaneGuidance(emptyList(), isRerouting = false))
    }

    @Test
    fun hidesLaneHintsDuringRerouting() {
        assertFalse(shouldShowLaneGuidance(lanes, isRerouting = true))
        assertFalse(shouldShowLaneGuidance(emptyList(), isRerouting = true))
    }
}
