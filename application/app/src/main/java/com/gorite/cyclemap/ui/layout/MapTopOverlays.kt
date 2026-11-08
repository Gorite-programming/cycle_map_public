package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gorite.cyclemap.routing.OffRouteDetector
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.ui.map.toGuidanceTurnType
import com.gorite.cyclemap.ui.cycling.QuickSpotType
import com.gorite.cyclemap.ui.theme.Motion

@Composable
internal fun BoxScope.MapTopOverlays(
    isNavigationActive: Boolean,
    effectiveAnimationEnabled: Boolean,
    effectiveTopPadding: Dp,
    isLandscape: Boolean,
    systemInsets: PaddingValues,
    onMenuClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onHomeClick: () -> Unit,
    onSelectQuickSpot: (QuickSpotType) -> Unit,
    onSelectCategory: (String) -> Unit,
    notificationBannerGroup: @Composable (Modifier) -> Unit,
    navigationProgress: RouteProgress?,
    navigationInstructions: List<RouteInstruction>,
    gpsStatus: com.gorite.cyclemap.tracking.GpsSignalStatus,
    isRerouting: Boolean,
    offRouteDetector: OffRouteDetector,
    autoRerouteEnabled: Boolean,
    networkLink: com.gorite.cyclemap.net.Link = com.gorite.cyclemap.net.Link.Offline,
    onStopNavigation: () -> Unit = {},
) {
    // Normal Top Bar
    AnimatedVisibility(
        visible = !isNavigationActive,
        enter = Motion.topBarEnter(effectiveAnimationEnabled),
        exit = Motion.topBarExit(effectiveAnimationEnabled),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = effectiveTopPadding + 8.dp)
            .zIndex(2f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Google Maps 完全準拠 MapTopBar (≡ + 検索 + 雲/オフライン + 🎙️ + アバター + 通知バナー + カテゴリチップ)
            com.gorite.cyclemap.ui.map.MapTopBar(
                queryText = "",
                onSearchClick = onSearchClick,
                onMenuClick = onMenuClick,
                networkLink = networkLink,
                selectedCategory = null,
                onCategoryClick = onSelectCategory,
                onMicClick = onSearchClick,
                onAvatarClick = onAvatarClick,
                userAvatarText = "",
                placeholderText = "ここで検索",
                bannerContent = {
                    notificationBannerGroup(Modifier.fillMaxWidth())
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 案内中: 地図の地名ラベルがステータスバーの時計・アイコンと重ならないよう、上端に薄い影を敷く
    if (isNavigationActive) {
        val scrimBase = androidx.compose.material3.MaterialTheme.colorScheme.background
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(systemInsets.calculateTopPadding() + 20.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        // ステータスバーの高さまではほぼ不透明に保ち、その下で薄く消す
                        0f to scrimBase.copy(alpha = 0.92f),
                        0.6f to scrimBase.copy(alpha = 0.85f),
                        1f to androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
                .zIndex(1f),
        )
    }

    // Top Center: Active Navigation Guidance & Banners (isNavigationActive: GoogleNavHeader)
    AnimatedVisibility(
        visible = isNavigationActive,
        enter = Motion.topBarEnter(effectiveAnimationEnabled),
        exit = Motion.topBarExit(effectiveAnimationEnabled),
        modifier = Modifier
            .align(if (isLandscape) Alignment.TopStart else Alignment.TopCenter)
            .then(
                if (isLandscape) {
                    Modifier.padding(
                        start = systemInsets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp,
                        top = effectiveTopPadding + 8.dp,
                    )
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .padding(top = effectiveTopPadding + 8.dp)
                },
            )
            .zIndex(2f),
    ) {
        val progress = navigationProgress
        val distanceMeters = progress?.distanceToNextManeuverMeters
            ?: progress?.let { (it.routeDistanceMeters - it.distanceFromStartMeters).coerceAtLeast(0.0) }
        val remaining = progress?.let { (it.routeDistanceMeters - it.distanceFromStartMeters).coerceAtLeast(0.0) }
        val guide = if (distanceMeters != null) {
            navigationInstruction(progress, remaining, navigationInstructions, ARRIVAL_RADIUS_METERS)
        } else null

        val traveled = progress?.distanceFromStartMeters ?: 0.0
        val futureInstructions = navigationInstructions.filter { it.distanceFromStartMeters > traveled }
        val nextInstruction = futureInstructions.firstOrNull()
        val nextAfterThis = futureInstructions.getOrNull(1)
        val distNextAfterThis = nextAfterThis?.let { (it.distanceFromStartMeters - traveled).coerceAtLeast(0.0) }

        // 車線は、次の曲がり角の手前の道の turn:lanes (graph の車線区間) から。無い道では何も出さない
        val laneProvider = androidx.compose.runtime.remember(nextInstruction?.incomingTurnLanes) {
            val lanes = nextInstruction?.incomingTurnLanes
            com.gorite.cyclemap.real.RealLaneGuidanceProvider(laneDataSource = { lanes })
        }
        var activeLaneHints by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<List<com.gorite.cyclemap.contract.LaneInfo>>(emptyList())
        }
        val currentTurnType = nextInstruction.toGuidanceTurnType()
        val nextTurnType = nextAfterThis.toGuidanceTurnType()

        androidx.compose.runtime.LaunchedEffect(nextInstruction?.nodeId, currentTurnType, laneProvider) {
            val hints = laneProvider.getLaneHints(nextInstruction?.nodeId ?: 0L, currentTurnType)
            val hintsFromLaneHint: List<com.gorite.cyclemap.routing.LaneHint> = hints.map {
                com.gorite.cyclemap.routing.LaneHint(
                    direction = it.direction.name.lowercase(),
                    isActiveForRoute = it.isRecommended
                )
            }
            activeLaneHints = hintsFromLaneHint.take(5).map {
                com.gorite.cyclemap.contract.LaneInfo(
                    direction = when (it.direction) {
                        "left" -> com.gorite.cyclemap.contract.TurnType.Left
                        "slightleft" -> com.gorite.cyclemap.contract.TurnType.SlightLeft
                        "sharpleft" -> com.gorite.cyclemap.contract.TurnType.SharpLeft
                        "right" -> com.gorite.cyclemap.contract.TurnType.Right
                        "slightright" -> com.gorite.cyclemap.contract.TurnType.SlightRight
                        "sharpright" -> com.gorite.cyclemap.contract.TurnType.SharpRight
                        "roundabout" -> com.gorite.cyclemap.contract.TurnType.Roundabout
                        "uturn" -> com.gorite.cyclemap.contract.TurnType.Uturn
                        else -> com.gorite.cyclemap.contract.TurnType.Straight
                    },
                    isRecommended = it.isActiveForRoute
                )
            }
        }

        if (isLandscape) {
            com.gorite.cyclemap.ui.map.LandscapeNavHudBar(
                currentStep = com.gorite.cyclemap.contract.GuidanceStep(
                    turnType = currentTurnType,
                    instructionText = guide?.text?.withoutDistancePrefix() ?: "直進",
                    streetName = null,
                    distanceToTurnMeters = distanceMeters ?: 0.0
                ),
                eta = null,
                remainingSeconds = (remaining ?: 0.0) / 4.2,
                onStopNavigation = onStopNavigation,
                animationEnabled = effectiveAnimationEnabled
            )
        } else {
            com.gorite.cyclemap.ui.map.NavTopBarC(
                turnType = currentTurnType,
                distanceMeters = distanceMeters ?: 0.0,
                instructionText = guide?.text?.withoutDistancePrefix() ?: "直進",
                // 道路名 (「県道41号」など) があればそれを、無ければ道路の区分 (「住宅街の道」など) を出す
                roadLabel = nextInstruction?.outgoingRoadName ?: com.gorite.cyclemap.ui.map.roadClassLabel(nextInstruction?.outgoingRoadType),
                next = if (nextInstruction != null && nextAfterThis != null) {
                    com.gorite.cyclemap.ui.map.NavNextHint(
                        turnType = nextTurnType,
                        distanceMeters = (nextAfterThis.distanceFromStartMeters - nextInstruction.distanceFromStartMeters).coerceAtLeast(0.0),
                    )
                } else null,
                isRerouting = isRerouting,
            )
        }
    }
}

private fun String.withoutDistancePrefix(): String = replace(
    Regex("^\\s*(?:あと)?\\d+(?:\\.\\d+)?\\s*(?:m|km)(?:先|後)?\\s*"),
    "",
).ifBlank { this }
