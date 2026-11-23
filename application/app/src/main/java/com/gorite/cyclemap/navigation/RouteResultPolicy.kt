package com.gorite.cyclemap.navigation

/** 探索された経路が目的地まで完全に到達しているか、県境などで止まる部分経路か。 */
internal enum class RouteCompleteness {
    /** すべての区間・経由地・目的地まで完全につながっている経路。 */
    COMPLETE,

    /** 県またぎに失敗し出発県のグラフだけで引いた、目的地まで届かない部分経路。 */
    PARTIAL,
}

/** 県またぎ計画の結果分類（単一グラフでのフォールバック判定と警告生成に使う）。 */
internal sealed interface CrossPlanningOutcome {
    data object NotNeeded : CrossPlanningOutcome
    data object Succeeded : CrossPlanningOutcome
    data class MissingPrefectureData(val missingPrefectures: List<String>) : CrossPlanningOutcome
    data class WaypointUnreachable(val waypointNumber: Int) : CrossPlanningOutcome
    data object Cancelled : CrossPlanningOutcome
    data object Truncated : CrossPlanningOutcome
    data object Failed : CrossPlanningOutcome
}

/** 探索結果を現在の案内・表示に反映するか、既存の案内を保持するかの判断。 */
internal data class RouteApplicationDecision(
    val completeness: RouteCompleteness?,
    val shouldReplaceRoute: Boolean,
    val warningMessage: String?,
)

internal fun waypointUnreachableMessage(waypointNumber: Int): String =
    "経由地 ${waypointNumber} に寄れません"

internal fun rerouteMissingPrefectureMessage(missingPrefectures: List<String>): String =
    "隣の県のデータが無いため、再探索できません (${missingPrefectures.joinToString("・")})"

internal fun manualMissingPrefecturePartialMessage(missingPrefectures: List<String>): String =
    "隣の県のデータが無いため、出発地の県内で目的地に最も近い地点までの経路を表示しています (${missingPrefectures.joinToString("・")})"

internal fun manualMissingPrefectureUnreachableMessage(missingPrefectures: List<String>): String =
    "${missingPrefectures.joinToString("・")}のルートグラフがありません"

private const val REROUTE_UNREACHABLE_KEEP_MESSAGE =
    "再探索で有効なルートが見つかりませんでした。元のルートを維持します"

private const val MANUAL_UNREACHABLE_MESSAGE =
    "有効な自転車ルートが見つかりませんでした。出発地や目的地が道路から離れている、または地図データの範囲外の可能性があります"

private const val TRUNCATED_ROUTE_MESSAGE =
    "探索範囲が大きすぎて打ち切りました。近めの目的地でお試しください"

/**
 * 停止点列の県名と端末に配備済みの県一覧から、県またぎ経路に必要なのに端末に無い県名を順序通りに抽出する。
 * 終点側だけでなく、中継県が欠落してチェーンを組めない場合も全県隣接グラフから不足県を特定する。
 */
internal fun findMissingPrefecturesForRoute(
    stopPrefectures: List<String?>,
    availablePrefectures: Set<String>,
    allPrefectures: Set<String>,
    centers: Map<String, CrossGeoPoint>,
): List<String> {
    val missing = LinkedHashSet<String>()
    for (pref in stopPrefectures) {
        if (pref != null && pref !in availablePrefectures) {
            missing += pref
        }
    }
    for ((fromPref, toPref) in stopPrefectures.zipWithNext()) {
        if (fromPref == null || toPref == null || fromPref == toPref) continue
        if (planPrefectureChains(fromPref, toPref, availablePrefectures, centers).isEmpty()) {
            val idealChain = planPrefectureChains(fromPref, toPref, allPrefectures, centers).firstOrNull()
            if (idealChain != null) {
                for (pref in idealChain) {
                    if (pref !in availablePrefectures) missing += pref
                }
            }
        }
    }
    return missing.toList()
}

/** 探索が完全な経路か、県またぎに失敗した出発県内だけの部分経路かを判定する。 */
internal fun classifyRouteCompleteness(
    needsCrossPlanning: Boolean,
    usedCrossRoute: Boolean,
    singleGraphReachable: Boolean,
): RouteCompleteness? = when {
    usedCrossRoute -> RouteCompleteness.COMPLETE
    !needsCrossPlanning && singleGraphReachable -> RouteCompleteness.COMPLETE
    needsCrossPlanning && singleGraphReachable -> RouteCompleteness.PARTIAL
    else -> null
}

/**
 * 県またぎに失敗したあと単一グラフへのフォールバック探索を行うべきか判定する。
 * 自動再探索では部分経路への置き換えを行わないため、県またぎ失敗時に単一グラフへ落とさない。
 * 経由地に寄れないと判明している場合もフォールバックしない。
 */
internal fun shouldAttemptSingleGraphFallback(
    isReroute: Boolean,
    usedCrossRoute: Boolean,
    crossOutcome: CrossPlanningOutcome,
    needsCrossPlanning: Boolean,
): Boolean {
    if (usedCrossRoute) return false
    if (crossOutcome is CrossPlanningOutcome.WaypointUnreachable) return false
    if (crossOutcome is CrossPlanningOutcome.Cancelled) return false
    if (crossOutcome is CrossPlanningOutcome.Truncated) return false
    if (isReroute && needsCrossPlanning) return false
    return true
}

/**
 * 完全/部分の区別・再探索フラグ・県またぎ失敗理由から、既存の経路を置き換えるか、
 * どの警告メッセージを表示するかを決定する。
 */
internal fun decideRouteApplication(
    isReroute: Boolean,
    completeness: RouteCompleteness?,
    crossOutcome: CrossPlanningOutcome,
    wasTruncated: Boolean = false,
    singleGraphWaypointFailure: Int? = null,
): RouteApplicationDecision {
    val waypointFailure = (crossOutcome as? CrossPlanningOutcome.WaypointUnreachable)?.waypointNumber
        ?: singleGraphWaypointFailure
    if (waypointFailure != null) {
        return RouteApplicationDecision(
            completeness = null,
            shouldReplaceRoute = false,
            warningMessage = waypointUnreachableMessage(waypointFailure),
        )
    }
    if (wasTruncated || crossOutcome is CrossPlanningOutcome.Truncated) {
        return RouteApplicationDecision(
            completeness = null,
            shouldReplaceRoute = false,
            warningMessage = TRUNCATED_ROUTE_MESSAGE,
        )
    }

    val missing = (crossOutcome as? CrossPlanningOutcome.MissingPrefectureData)?.missingPrefectures.orEmpty()

    return when (completeness) {
        RouteCompleteness.COMPLETE -> RouteApplicationDecision(
            completeness = RouteCompleteness.COMPLETE,
            shouldReplaceRoute = true,
            warningMessage = if (crossOutcome == CrossPlanningOutcome.Succeeded) {
                CROSS_PREFECTURE_ROUTE_MESSAGE
            } else {
                null
            },
        )
        RouteCompleteness.PARTIAL -> if (isReroute) {
            RouteApplicationDecision(
                completeness = RouteCompleteness.PARTIAL,
                shouldReplaceRoute = false,
                warningMessage = if (missing.isNotEmpty()) {
                    rerouteMissingPrefectureMessage(missing)
                } else {
                    REROUTE_UNREACHABLE_KEEP_MESSAGE
                },
            )
        } else {
            RouteApplicationDecision(
                completeness = RouteCompleteness.PARTIAL,
                shouldReplaceRoute = true,
                warningMessage = if (missing.isNotEmpty()) {
                    manualMissingPrefecturePartialMessage(missing)
                } else {
                    CROSS_PREFECTURE_UNSUPPORTED_MESSAGE
                },
            )
        }
        null -> RouteApplicationDecision(
            completeness = null,
            shouldReplaceRoute = false,
            warningMessage = if (isReroute) {
                if (missing.isNotEmpty()) {
                    rerouteMissingPrefectureMessage(missing)
                } else {
                    REROUTE_UNREACHABLE_KEEP_MESSAGE
                }
            } else {
                if (missing.isNotEmpty()) {
                    manualMissingPrefectureUnreachableMessage(missing)
                } else {
                    MANUAL_UNREACHABLE_MESSAGE
                }
            },
        )
    }
}
