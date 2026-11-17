package com.gorite.cyclemap.ui.state

import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import com.gorite.cyclemap.routing.RoadRuleKind

/** 道路のルールの通知を出すかの設定と、今知らせる内容。深い所の画面から読むための入口 (LocalDisplayModeController と同じ流儀)。 */
class RoadRuleController(
    val enabled: State<Boolean>,
    val setEnabled: (Boolean) -> Unit,
    /** 今知らせる種類。知らせることが無いときは null。 */
    val kind: State<RoadRuleKind?>,
)

val LocalRoadRuleController = compositionLocalOf<RoadRuleController?> { null }

internal const val PREF_ROAD_RULE_ENABLED = "road_rule_enabled"
