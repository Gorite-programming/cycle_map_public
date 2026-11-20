package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gorite.cyclemap.net.Link
import com.gorite.cyclemap.ui.cycling.GoogleFloatingActionButton
import com.gorite.cyclemap.ui.cycling.GoogleGpsButton
import com.gorite.cyclemap.ui.cycling.NavStartFAB
import com.gorite.cyclemap.ui.map.MapControls
import com.gorite.cyclemap.ui.map.NetworkStatusIndicator
import com.gorite.cyclemap.ui.theme.LocalAnimationEnabled
import com.gorite.cyclemap.ui.theme.Motion
import kotlinx.coroutines.delay
import org.maplibre.android.maps.MapLibreMap
import org.osmdroid.views.MapView

@Composable
internal fun BoxScope.MapControlsOverlay(
    isLandscape: Boolean,
    selectedLayer: MapLayer,
    networkLink: Link,
    vectorMap: MapLibreMap?,
    mapView: MapView?,
    currentZoomLevel: Double,
    currentVectorTilt: Double,
    vectorBearing: Double = 0.0,
    headingDegrees: Float,
    isHeadingUp: Boolean,
    onHeadingUpToggle: () -> Unit,
    followLocation: Boolean,
    onTargetClick: () -> Unit,
    isNavigationActive: Boolean,
    isAreaSelectMode: Boolean,
    onAreaSelectToggle: () -> Unit,
    toggleNextLayer: () -> Unit,
    onNavStartClick: () -> Unit,
    systemInsets: PaddingValues,
    effectiveTopPadding: Dp,
    bottomHudVisible: Boolean = false,
    isRoutePreviewActive: Boolean = false,
    /** 案内中の上部カードの高さ。0 なら未計測で、従来の固定位置 (104dp) を使う。 */
    navHeaderHeight: Dp = 0.dp,
    /** 上部オーバーレイ全体の高さ (effectiveTopPadding + 8.dp は含まない)。0 なら非表示または未計測。 */
    topOverlayHeight: Dp = 0.dp,
    /** ルート確認シートの高さ (画面下端からシート上端まで)。0 なら未計測。 */
    routePreviewSheetHeight: Dp = 0.dp,
    /** ナビ中の「立ち寄り」ボタン押下時にクイック検索シートを開くコールバック。 */
    onQuickSpotClick: (() -> Unit)? = null,
) {
    val canZoomIn = if (selectedLayer == MapLayer.VECTOR_LIBERTY) {
        (vectorMap?.cameraPosition?.zoom ?: 14.0) < 22.0
    } else {
        currentZoomLevel < (mapView?.maxZoomLevel ?: 21.0)
    }
    val canZoomOut = if (selectedLayer == MapLayer.VECTOR_LIBERTY) {
        (vectorMap?.cameraPosition?.zoom ?: 14.0) > 2.0
    } else {
        currentZoomLevel > (mapView?.minZoomLevel ?: 2.0)
    }
    val currentLayerLabel = when (selectedLayer) {
        MapLayer.VECTOR_LIBERTY -> "3Dベクター"
        MapLayer.GSI -> "標準"
        MapLayer.OSM -> "自転車"
        MapLayer.TERRAIN -> "地形"
    }
    val mapBearing = if (selectedLayer == MapLayer.VECTOR_LIBERTY) {
        vectorBearing.toFloat()
    } else {
        mapView?.mapOrientation ?: 0f
    }
    val onToggleTiltAction: () -> Unit = {
        val map = vectorMap
        if (map != null) {
            val targetTilt = if (currentVectorTilt > 15.0) 0.0 else 60.0
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    org.maplibre.android.camera.CameraPosition.Builder(map.cameraPosition)
                        .tilt(targetTilt)
                        .build(),
                ),
                700,
            )
        }
    }
    // ベクター地図のズームは毎フレーム moveCamera で進める (案内中の追従カメラに打ち切られないため)
    val zoomAnimator = androidx.compose.runtime.remember(vectorMap) {
        com.gorite.cyclemap.ui.mapview.MapZoomAnimator(
            getMap = { vectorMap },
            isUserGestureInProgress = {
                val gestures = vectorMap?.gesturesManager
                gestures != null && (
                    gestures.standardScaleGestureDetector.isInProgress ||
                        gestures.rotateGestureDetector.isInProgress ||
                        gestures.shoveGestureDetector.isInProgress ||
                        gestures.moveGestureDetector.isInProgress
                    )
            },
        )
    }
    val onZoomInAction: () -> Unit = {
        if (selectedLayer == MapLayer.VECTOR_LIBERTY) {
            zoomAnimator.zoomBy(1.0)
        } else {
            mapView?.controller?.zoomIn(150L)
        }
    }
    val onZoomOutAction: () -> Unit = {
        if (selectedLayer == MapLayer.VECTOR_LIBERTY) {
            zoomAnimator.zoomBy(-1.0)
        } else {
            mapView?.controller?.zoomOut(150L)
        }
    }

    if (false) {
        MapControls(bearingDeg = mapBearing, onResetNorth = onHeadingUpToggle, onLayerClick = toggleNextLayer, onMyLocationClick = onTargetClick, onZoomIn = onZoomInAction, onZoomOut = onZoomOutAction)
        NetworkStatusIndicator(networkLink)
    }
    if (isLandscape) {
        // 横画面のナビ中は、案内ヘッダが左上にあり操作ボタンの列と重なるので、左手でも右に残す
        val isSimple = com.gorite.cyclemap.ui.state.isSimpleMode()
        val showTopBar = !isNavigationActive && !isRoutePreviewActive && !isSimple
        val landscapeLeft = com.gorite.cyclemap.ui.state.isLeftHanded() && !isNavigationActive
        val landscapeTopPadding = resolveLandscapeControlsTopPadding(
            effectiveTopPadding = effectiveTopPadding,
            topOverlayHeight = topOverlayHeight,
            isNavigationActive = isNavigationActive,
            showTopBar = showTopBar,
        )
        LandscapeControlRow(
            mapBearing = mapBearing,
            isHeadingUp = isHeadingUp,
            onCompassClick = onHeadingUpToggle,
            currentTilt = currentVectorTilt,
            onToggleTilt = onToggleTiltAction,
            selectedLayer = selectedLayer,
            layerLabel = currentLayerLabel,
            onFallbackLayerToggle = toggleNextLayer,
            following = followLocation,
            onTargetClick = onTargetClick,
            onZoomIn = onZoomInAction,
            onZoomOut = onZoomOutAction,
            canZoomIn = canZoomIn,
            canZoomOut = canZoomOut,
            isNavigationActive = isNavigationActive,
            isAreaSelectMode = isAreaSelectMode,
            onAreaSelectToggle = onAreaSelectToggle,
            onNavStartClick = onNavStartClick,
            onQuickSpotClick = onQuickSpotClick,
            modifier = Modifier
                .align(com.gorite.cyclemap.ui.state.controlsTopAlignment(landscapeLeft))
                .padding(
                    start = if (landscapeLeft) systemInsets.calculateStartPadding(LayoutDirection.Ltr) + 12.dp else 0.dp,
                    end = if (landscapeLeft) 0.dp else systemInsets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
                    top = landscapeTopPadding,
                )
                .zIndex(2f),
        )
    } else if (!isNavigationActive) {
        // 右上 (チップ列の直下): レイヤー切替ボタン + 回転時のみコンパス (Google Maps 配置)
        Column(
            modifier = Modifier
                .align(com.gorite.cyclemap.ui.state.controlsTopAlignment(com.gorite.cyclemap.ui.state.isLeftHanded()))
                .padding(
                    top = if (topOverlayHeight > 0.dp) effectiveTopPadding + topOverlayHeight + 16.dp
                        else effectiveTopPadding + (if (isRoutePreviewActive || com.gorite.cyclemap.ui.state.isSimpleMode()) 12.dp else 122.dp),
                    start = if (com.gorite.cyclemap.ui.state.isLeftHanded()) 14.dp else 0.dp,
                    end = if (com.gorite.cyclemap.ui.state.isLeftHanded()) 0.dp else 14.dp,
                )
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LayerPickerButton(
                selectedLayer = selectedLayer,
                fallbackLabel = currentLayerLabel,
                onFallbackToggle = toggleNextLayer,
            )
            // 3D / 2D トグルボタン (縦画面)
            GoogleFloatingActionButton(
                onClick = onToggleTiltAction,
                contentDescription = if (currentVectorTilt > 15.0) "2D表示へ切替" else "3D表示へ切替",
            ) {
                Text(
                    text = if (currentVectorTilt > 15.0) "2D" else "3D",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (currentVectorTilt > 15.0) CycleMapTheme.palette.accent else CycleMapTheme.palette.mapControlIcon,
                )
            }
            // エリア選択ボタン (縦画面。シンプルモードでは出さない)
            if (!com.gorite.cyclemap.ui.state.isSimpleMode()) Surface(
                shape = if (isAreaSelectMode) RoundedCornerShape(22.dp) else CircleShape,
                color = if (isAreaSelectMode) MaterialTheme.colorScheme.primary else CycleMapTheme.palette.mapControlSurface,
                shadowElevation = 6.dp,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onAreaSelectToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_download),
                        contentDescription = if (isAreaSelectMode) "エリア選択中" else "エリアダウンロード",
                        modifier = Modifier.size(22.dp),
                        tint = if (isAreaSelectMode) MaterialTheme.colorScheme.onPrimary else CycleMapTheme.palette.mapControlIcon,
                    )
                }
            }
            val safeBearing = if (mapBearing.isNaN()) 0f else mapBearing
            AnimatedVisibility(
                visible = kotlin.math.abs(safeBearing) > 2.0f,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TouchTargetCircleButton48(
                    onClick = onHeadingUpToggle,
                    contentDescription = "北を上に",
                    visualSize = 42.dp,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_compass),
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(-safeBearing),
                        tint = CycleMapTheme.palette.danger,
                    )
                }
            }
            // ルート確認中はシートが高く、右下の現在地ボタンと重なるので出さない (2 本指で拡大縮小できる)
            if (!isRoutePreviewActive) {
                ZoomPill48(
                    onZoomIn = onZoomInAction,
                    onZoomOut = onZoomOutAction,
                    canZoomIn = canZoomIn,
                    canZoomOut = canZoomOut,
                )
            }
        }

        // 右下 (ボトムシートの直上): 現在地ボタン (白丸) + 経路検索FAB (ティール角丸四角)
        // ルート確認中はシートの上端から 12dp 上に現在地ボタンだけを置く (未計測時は隠す)
        // 通常時は下部の速度・GPX 記録の行が出ている間、その分だけ持ち上げて重ならないようにする
        val bottomSheetOffset = if (isRoutePreviewActive && routePreviewSheetHeight > 0.dp) {
            routePreviewSheetHeight + 12.dp
        } else {
            // シンプルモードは下のシートが無いので、持ち上げる量を小さくする
            systemInsets.calculateBottomPadding() + (if (com.gorite.cyclemap.ui.state.isSimpleMode()) 24.dp else 176.dp) + (if (bottomHudVisible) 68.dp else 0.dp)
        }
        Column(
            modifier = Modifier
                .align(com.gorite.cyclemap.ui.state.controlsBottomAlignment(com.gorite.cyclemap.ui.state.isLeftHanded()))
                .padding(start = if (com.gorite.cyclemap.ui.state.isLeftHanded()) 14.dp else 0.dp, end = if (com.gorite.cyclemap.ui.state.isLeftHanded()) 0.dp else 14.dp, bottom = bottomSheetOffset)
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isRoutePreviewActive) {
                GoogleGpsButton(
                    following = followLocation,
                    onClick = onTargetClick,
                )
                NavStartFAB(
                    onClick = onNavStartClick,
                )
            } else if (routePreviewSheetHeight > 0.dp) {
                GoogleGpsButton(
                    following = followLocation,
                    onClick = onTargetClick,
                )
            }
        }
    } else {
        // 案内中も地図上部の右側に主要操作を残し、Google Mapsのナビ画面に近い操作配置にする。
        Column(
            modifier = Modifier
                .align(com.gorite.cyclemap.ui.state.controlsTopAlignment(com.gorite.cyclemap.ui.state.isLeftHanded()))
                // 案内カードは「そのあと」の帯・2 行の案内文・車線で高さが変わるので、測った高さの下に置く
                .padding(
                    top = effectiveTopPadding + if (navHeaderHeight > 0.dp) navHeaderHeight + 8.dp + 12.dp else 104.dp,
                    start = if (com.gorite.cyclemap.ui.state.isLeftHanded()) 14.dp else 0.dp,
                    end = if (com.gorite.cyclemap.ui.state.isLeftHanded()) 0.dp else 14.dp,
                )
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TouchTargetCircleButton48(
                onClick = onHeadingUpToggle,
                contentDescription = "コンパス・北向き切替",
                visualSize = 42.dp,
            ) {
                val safeBearing = if (mapBearing.isNaN()) 0f else mapBearing
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_compass),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp).rotate(-safeBearing),
                    tint = CycleMapTheme.palette.danger,
                )
            }
            TouchTargetCircleButton48(
                onClick = onZoomInAction,
                contentDescription = "拡大",
                visualSize = 42.dp,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ms_add),
                    contentDescription = null,
                    tint = CycleMapTheme.palette.mapControlIcon,
                    modifier = Modifier.size(20.dp),
                )
            }
            TouchTargetCircleButton48(
                onClick = onZoomOutAction,
                contentDescription = "縮小",
                visualSize = 42.dp,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_minus),
                    contentDescription = null,
                    tint = CycleMapTheme.palette.mapControlIcon,
                    modifier = Modifier.size(20.dp),
                )
            }
            GoogleGpsButton(
                following = followLocation,
                onClick = onTargetClick,
            )
            // ナビ中の立ち寄り先検索ボタン (シンプルモードでは出さない)
            if (!com.gorite.cyclemap.ui.state.isSimpleMode() && onQuickSpotClick != null) {
                GoogleFloatingActionButton(
                    onClick = onQuickSpotClick,
                    iconRes = R.drawable.ic_lucide_store,
                    contentDescription = "立ち寄り",
                    tint = CycleMapTheme.palette.accent,
                )
            }
        }
    }
}

/**
 * 見た目の円サイズ (例: 42dp) を維持したまま、タップ領域を 48dp 以上にそろえるボタン。
 */
@Composable
private fun TouchTargetCircleButton48(
    onClick: () -> Unit,
    contentDescription: String,
    visualSize: Dp = 42.dp,
    shadowElevation: Dp = 5.dp,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = CycleMapTheme.palette.mapControlSurface,
            tonalElevation = 0.dp,
            shadowElevation = shadowElevation,
            modifier = Modifier
                .size(visualSize)
                .clip(CircleShape)
                .indication(interactionSource, LocalIndication.current),
        ) {
            Box(contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
private fun ZoomRepeatButton48(
    iconRes: Int,
    contentDescription: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val currentOnAction by rememberUpdatedState(onAction)
    val isPressed = remember { mutableStateOf(false) }

    LaunchedEffect(isPressed.value, enabled) {
        if (isPressed.value && enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            currentOnAction()
            delay(400L)
            while (isPressed.value && enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnAction()
                delay(180L)
            }
        }
    }

    val alpha = if (enabled) 1.0f else 0.35f
    val animEnabled = LocalAnimationEnabled.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed.value && enabled) 0.90f else 1.0f,
        animationSpec = Motion.motionTween(Motion.DURATION_SHORT, enabled = animEnabled),
        label = "ZoomBtn48Scale",
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onClick(label = contentDescription) {
                    if (enabled) currentOnAction()
                    enabled
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (isPressed.value && enabled) {
                    Modifier.background(CycleMapTheme.palette.mapControlDivider)
                } else {
                    Modifier
                }
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        isPressed.value = true
                        try {
                            awaitRelease()
                        } finally {
                            isPressed.value = false
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = CycleMapTheme.palette.mapControlIcon.copy(alpha = alpha),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ZoomPill48(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = CycleMapTheme.palette.mapControlSurface,
        shadowElevation = 6.dp,
        modifier = modifier.width(48.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ZoomRepeatButton48(
                iconRes = R.drawable.ic_ms_add,
                contentDescription = "ズームイン",
                onAction = onZoomIn,
                enabled = canZoomIn,
            )
            HorizontalDivider(
                color = CycleMapTheme.palette.mapControlDivider,
                thickness = 1.dp,
                modifier = Modifier.width(32.dp),
            )
            ZoomRepeatButton48(
                iconRes = R.drawable.ic_ms_remove,
                contentDescription = "ズームアウト",
                onAction = onZoomOut,
                enabled = canZoomOut,
            )
        }
    }
}

@Composable
private fun CompassDial48(
    headingDegrees: () -> Float,
    headingUp: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val pal = CycleMapTheme.palette
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "コンパス・北向き切替" }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = pal.mapControlSurface,
            shadowElevation = 6.dp,
            border = if (headingUp) {
                BorderStroke(2.dp, pal.compassNorth)
            } else {
                BorderStroke(1.dp, pal.mapControlDivider)
            },
        ) {
            Canvas(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .indication(interactionSource, LocalIndication.current),
            ) {
                val rotation = -headingDegrees()
                rotate(rotation) {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val r = size.minDimension / 2 - 6.dp.toPx()
                    val needle = Path().apply {
                        moveTo(cx, cy - r - 2.dp.toPx())
                        lineTo(cx - 5.dp.toPx(), cy - 2.dp.toPx())
                        lineTo(cx + 5.dp.toPx(), cy - 2.dp.toPx())
                        close()
                    }
                    drawPath(needle, pal.compassNorth)
                    val southNeedle = Path().apply {
                        moveTo(cx, cy + r + 2.dp.toPx())
                        lineTo(cx - 5.dp.toPx(), cy + 2.dp.toPx())
                        lineTo(cx + 5.dp.toPx(), cy + 2.dp.toPx())
                        close()
                    }
                    drawPath(southNeedle, pal.compassSouth)
                    val label = { text: String, color: Color, dx: Float, dy: Float ->
                        val layout = measurer.measure(
                            text,
                            TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = color,
                            ),
                        )
                        drawText(
                            measurer,
                            text,
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = color,
                            ),
                            topLeft = Offset(
                                cx + dx - layout.size.width / 2,
                                cy + dy - layout.size.height / 2,
                            ),
                        )
                    }
                    label("N", pal.compassNorth, 0f, -r + 8.dp.toPx())
                    label("E", pal.mapControlIconMuted, r - 8.dp.toPx(), 0f)
                    label("S", pal.mapControlIconMuted, 0f, r - 8.dp.toPx())
                    label("W", pal.mapControlIconMuted, -(r - 8.dp.toPx()), 0f)
                }
            }
        }
    }
}

@Composable
private fun LandscapeControlRow(
    mapBearing: Float,
    isHeadingUp: Boolean,
    onCompassClick: () -> Unit,
    currentTilt: Double,
    onToggleTilt: () -> Unit,
    selectedLayer: MapLayer,
    layerLabel: String,
    onFallbackLayerToggle: () -> Unit,
    following: Boolean,
    onTargetClick: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    isNavigationActive: Boolean,
    isAreaSelectMode: Boolean,
    onAreaSelectToggle: () -> Unit,
    onNavStartClick: () -> Unit,
    onQuickSpotClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isNavigationActive && !com.gorite.cyclemap.ui.state.isSimpleMode()) {
            Surface(
                shape = if (isAreaSelectMode) RoundedCornerShape(22.dp) else CircleShape,
                color = if (isAreaSelectMode) MaterialTheme.colorScheme.primary else CycleMapTheme.palette.mapControlSurface,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onAreaSelectToggle)
                        .padding(horizontal = if (isAreaSelectMode) 10.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_lucide_square_dashed),
                            contentDescription = "エリア選択",
                            tint = if (isAreaSelectMode) MaterialTheme.colorScheme.onPrimary else CycleMapTheme.palette.mapControlIcon,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    if (isAreaSelectMode) {
                        Text(
                            text = "選択中",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
        }

        CompassDial48(
            headingDegrees = { mapBearing },
            headingUp = isHeadingUp,
            onClick = onCompassClick,
        )

        GoogleFloatingActionButton(
            onClick = onToggleTilt,
            contentDescription = if (currentTilt > 15.0) "2D表示へ切替" else "3D表示へ切替",
        ) {
            Text(
                text = if (currentTilt > 15.0) "2D" else "3D",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (currentTilt > 15.0) CycleMapTheme.palette.accent else CycleMapTheme.palette.mapControlIcon,
            )
        }

        LayerPickerButton(
            selectedLayer = selectedLayer,
            fallbackLabel = layerLabel,
            onFallbackToggle = onFallbackLayerToggle,
        )

        GoogleGpsButton(
            following = following,
            onClick = onTargetClick,
        )

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = CycleMapTheme.palette.mapControlSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.height(48.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZoomRepeatButton48(
                    iconRes = R.drawable.ic_ms_add,
                    contentDescription = "ズームイン",
                    onAction = onZoomIn,
                    enabled = canZoomIn,
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(CycleMapTheme.palette.mapControlDivider),
                )
                ZoomRepeatButton48(
                    iconRes = R.drawable.ic_ms_remove,
                    contentDescription = "ズームアウト",
                    onAction = onZoomOut,
                    enabled = canZoomOut,
                )
            }
        }

        if (isNavigationActive && !com.gorite.cyclemap.ui.state.isSimpleMode() && onQuickSpotClick != null) {
            GoogleFloatingActionButton(
                onClick = onQuickSpotClick,
                iconRes = R.drawable.ic_lucide_store,
                contentDescription = "立ち寄り",
                tint = CycleMapTheme.palette.accent,
            )
        }

        if (!isNavigationActive) {
            NavStartFAB(onClick = onNavStartClick)
        }
    }
}


/** レイヤー選択を実行する関数 (MapScreen が提供)。未提供のときは従来どおり順送りに切り替える。 */
internal val LocalLayerSelector = androidx.compose.runtime.staticCompositionLocalOf<((MapLayer) -> Unit)?> { null }

/**
 * 地図レイヤーのボタン。押すとレイヤー名の一覧を出し、今の種類にチェックを付ける。
 * (以前は押すたびに順送りで切り替わり、何に変わったか分からなかった)
 */
@Composable
private fun LayerPickerButton(
    selectedLayer: MapLayer,
    fallbackLabel: String,
    onFallbackToggle: () -> Unit,
) {
    val selector = LocalLayerSelector.current
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        TouchTargetCircleButton48(
            onClick = { if (selector != null) expanded = true else onFallbackToggle() },
            contentDescription = "地図レイヤー切替: $fallbackLabel",
            visualSize = 42.dp,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lucide_layers),
                contentDescription = null,
                tint = CycleMapTheme.palette.mapControlIcon,
                modifier = Modifier.size(20.dp),
            )
        }
        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MapLayer.entries.forEach { layer ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            layer.label,
                            fontWeight = if (layer == selectedLayer) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    leadingIcon = {
                        if (layer == selectedLayer) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lucide_check),
                                contentDescription = "選択中",
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        if (layer != selectedLayer) selector?.invoke(layer)
                    },
                )
            }
        }
    }
}

/**
 * 横画面の上部操作列の top padding を決める。
 * [topOverlayHeight] (effectiveTopPadding + 8.dp より下にある検索バー・バナー・チップ・ナビヘッダの実測高さ)
 * があるときは必ずその 8.dp 下に置き、互いに重ならないようにする。
 */
internal fun resolveLandscapeControlsTopPadding(
    effectiveTopPadding: Dp,
    topOverlayHeight: Dp,
    isNavigationActive: Boolean,
    showTopBar: Boolean,
): Dp {
    val contentOffset = when {
        topOverlayHeight > 0.dp -> 8.dp + topOverlayHeight + 8.dp
        isNavigationActive -> 104.dp
        showTopBar -> 132.dp
        else -> 12.dp
    }
    return effectiveTopPadding + contentOffset
}
