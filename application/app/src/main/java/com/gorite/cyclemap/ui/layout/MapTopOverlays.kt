package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gorite.cyclemap.net.Link
import com.gorite.cyclemap.routing.OffRouteDetector
import com.gorite.cyclemap.routing.RouteInstruction
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.ui.cycling.QuickSpotType
import com.gorite.cyclemap.ui.map.DEFAULT_CATEGORY_CHIPS
import com.gorite.cyclemap.ui.map.NetworkStatusIndicator
import com.gorite.cyclemap.ui.map.toGuidanceTurnType
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
    isRoutePreviewActive: Boolean = false,
    /** 案内中の上部カードの高さ (px)。右側の地図ボタンをその下に置くために使う。 */
    onNavHeaderHeightChanged: (Int) -> Unit = {},
    /** 上部オーバーレイ全体のコンテンツ高さ (px、effectiveTopPadding + 8.dp は含まない)。横画面の操作列をその下に置くために使う。 */
    onTopOverlayHeightChanged: (Int) -> Unit = {},
    roadRuleBadge: (@Composable () -> Unit)? = null,
) {
    val isSimple = com.gorite.cyclemap.ui.state.isSimpleMode()
    val showTopBar = !isNavigationActive && !isRoutePreviewActive && com.gorite.cyclemap.ui.state.SimpleModeVisibility.of(
        if (isSimple) com.gorite.cyclemap.ui.state.DisplayMode.SIMPLE else com.gorite.cyclemap.ui.state.DisplayMode.NORMAL,
        isNavigationActive,
    ).topBar

    var topBarHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var simpleBannerHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var navOverlayHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(isNavigationActive) {
        if (!isNavigationActive) {
            onNavHeaderHeightChanged(0)
            navOverlayHeightPx = 0
        }
    }
    LaunchedEffect(showTopBar, isNavigationActive) {
        if (!showTopBar) topBarHeightPx = 0
        if (isNavigationActive || showTopBar) simpleBannerHeightPx = 0
    }
    LaunchedEffect(showTopBar, isNavigationActive, topBarHeightPx, simpleBannerHeightPx, navOverlayHeightPx) {
        val activeHeightPx = when {
            isNavigationActive -> navOverlayHeightPx
            showTopBar -> topBarHeightPx
            else -> simpleBannerHeightPx
        }
        onTopOverlayHeightChanged(activeHeightPx)
    }

    // Normal Top Bar (ルートプレビュー中は検索バーとチップを隠し、地図を広く使う)
    AnimatedVisibility(
        visible = showTopBar,
        enter = Motion.topBarEnter(effectiveAnimationEnabled),
        exit = Motion.topBarExit(effectiveAnimationEnabled),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = effectiveTopPadding + 8.dp)
            .zIndex(2f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { if (showTopBar) topBarHeightPx = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchTopBar(
                onSearchClick = onSearchClick,
                onMenuClick = onMenuClick,
                networkLink = networkLink,
                selectedCategory = null,
                onCategoryClick = onSelectCategory,
                placeholderText = "ここで検索",
                bannerContent = {
                    notificationBannerGroup(Modifier.fillMaxWidth())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (roadRuleBadge != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 84.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    roadRuleBadge()
                }
            }
        }
    }

    // シンプルモード・ルート確認中: 上端中央に警告バナー・GPS 状態・道路ルール通知を出す (左右 74dp を空けて操作ボタンと重ならないようにする)
    if (!isNavigationActive && !showTopBar) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = effectiveTopPadding + 8.dp, start = 74.dp, end = 74.dp)
                .onSizeChanged { simpleBannerHeightPx = it.height }
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            notificationBannerGroup(Modifier.fillMaxWidth())
            roadRuleBadge?.invoke()
        }
    }

    // 案内中: 地図の地名ラベルがステータスバーの時計・アイコンと重ならないよう、上端に薄い影を敷く
    if (isNavigationActive) {
        StatusBarScrim(
            // systemInsets は Scaffold の内側で 0 になるので、ステータスバーの実際の高さを含む effectiveTopPadding を使う
            topInset = effectiveTopPadding,
            modifier = Modifier
                .align(Alignment.TopCenter)
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
            .padding(top = effectiveTopPadding + 8.dp)
            .zIndex(2f),
    ) {
        val progress = navigationProgress
        val remaining = progress?.let { (it.routeDistanceMeters - it.distanceFromStartMeters).coerceAtLeast(0.0) }
        val display = resolveNavGuidanceDisplay(progress, remaining, navigationInstructions, ARRIVAL_RADIUS_METERS)
        val targetInstruction = display.targetInstruction

        // 車線は、表示対象になっている曲がり角の手前の道の turn:lanes (graph の車線区間) から。無い道では何も出さない
        val laneProvider = androidx.compose.runtime.remember(targetInstruction?.incomingTurnLanes) {
            val lanes = targetInstruction?.incomingTurnLanes
            com.gorite.cyclemap.real.RealLaneGuidanceProvider(laneDataSource = { lanes })
        }
        var activeLaneHints by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<List<com.gorite.cyclemap.contract.LaneInfo>>(emptyList())
        }

        androidx.compose.runtime.LaunchedEffect(targetInstruction?.nodeId, display.turnType, laneProvider) {
            val hints = laneProvider.getLaneHints(targetInstruction?.nodeId ?: 0L, display.turnType)
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { if (isNavigationActive) navOverlayHeightPx = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .then(
                        if (isLandscape) {
                            Modifier.padding(
                                start = systemInsets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp,
                                end = systemInsets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
                            )
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                        },
                    )
                    // padding より後ろなので、カード自体の高さ (上の余白を含まない) が届く
                    .onSizeChanged { onNavHeaderHeightChanged(it.height) },
            ) {
                if (isLandscape) {
                    com.gorite.cyclemap.ui.map.LandscapeNavHudBar(
                        currentStep = com.gorite.cyclemap.contract.GuidanceStep(
                            turnType = display.turnType,
                            instructionText = display.instructionText,
                            streetName = null,
                            distanceToTurnMeters = display.distanceMeters
                        ),
                        eta = null,
                        remainingSeconds = (remaining ?: 0.0) / 4.2,
                        onStopNavigation = onStopNavigation,
                        animationEnabled = effectiveAnimationEnabled,
                        laneHints = activeLaneHints,
                        isRerouting = isRerouting,
                    )
                } else {
                    com.gorite.cyclemap.ui.map.NavTopBarC(
                        turnType = display.turnType,
                        distanceMeters = display.distanceMeters,
                        instructionText = display.instructionText,
                        // 道路名 (「県道41号」など) があればそれを、無ければ道路の区分 (「住宅街の道」など) を出す
                        roadLabel = targetInstruction?.outgoingRoadName
                            ?: com.gorite.cyclemap.ui.map.roadClassLabel(targetInstruction?.outgoingRoadType),
                        next = if (display.nextInstruction != null && display.distanceToNextMeters != null) {
                            com.gorite.cyclemap.ui.map.NavNextHint(
                                turnType = display.nextInstruction.toGuidanceTurnType(),
                                distanceMeters = display.distanceToNextMeters,
                            )
                        } else null,
                        isRerouting = isRerouting,
                        laneHints = activeLaneHints,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isLandscape) systemInsets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp else 74.dp,
                        end = if (isLandscape) systemInsets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp else 74.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                notificationBannerGroup(Modifier.fillMaxWidth())
                roadRuleBadge?.invoke()
            }
        }
    }
}

/**
 * 地図上部の検索バーとカテゴリチップ列。
 * 未実装のマイクボタンを省き、メニューボタン・各チップとも 48dp 以上のタップ領域を確保する。
 */
@Composable
private fun SearchTopBar(
    onSearchClick: () -> Unit,
    onMenuClick: () -> Unit,
    networkLink: Link,
    selectedCategory: String?,
    onCategoryClick: (String) -> Unit,
    placeholderText: String,
    bannerContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .clickable(role = Role.Button, onClick = onSearchClick),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onMenuClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_menu),
                        contentDescription = "メニュー",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = placeholderText,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )

                Box(modifier = Modifier.size(1.dp).alpha(0.01f)) {
                    NetworkStatusIndicator(networkLink)
                }
            }
        }

        if (bannerContent != null) {
            Spacer(modifier = Modifier.height(4.dp))
            bannerContent()
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DEFAULT_CATEGORY_CHIPS.forEach { chip ->
                val isSelected = selectedCategory == chip.id
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = { onCategoryClick(chip.id) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 3.dp,
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .indication(interactionSource, LocalIndication.current),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(chip.iconRes),
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = chip.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
