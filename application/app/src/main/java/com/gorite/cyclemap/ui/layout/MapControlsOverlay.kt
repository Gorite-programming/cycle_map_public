package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gorite.cyclemap.net.Link
import com.gorite.cyclemap.ui.cycling.GoogleFloatingActionButton
import com.gorite.cyclemap.ui.cycling.GoogleGpsButton
import com.gorite.cyclemap.ui.cycling.GoogleLayerButton
import com.gorite.cyclemap.ui.cycling.GoogleMapsControlColumn
import com.gorite.cyclemap.ui.cycling.GoogleZoomButton
import com.gorite.cyclemap.ui.cycling.GoogleZoomPill
import com.gorite.cyclemap.ui.cycling.NavStartFAB
import com.gorite.cyclemap.ui.map.FloatingCircleButton
import com.gorite.cyclemap.ui.map.MapControls
import com.gorite.cyclemap.ui.map.NetworkStatusIndicator
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
        GoogleMapsControlColumn(
            headingDegrees = { mapBearing },
            isHeadingUp = isHeadingUp,
            onCompassClick = onHeadingUpToggle,
            currentTilt = currentVectorTilt,
            onToggleTilt = onToggleTiltAction,
            layerLabel = currentLayerLabel,
            onLayerClick = toggleNextLayer,
            following = followLocation,
            onTargetClick = onTargetClick,
            onZoomIn = onZoomInAction,
            onZoomOut = onZoomOutAction,
            canZoomIn = canZoomIn,
            canZoomOut = canZoomOut,
            isNavigationActive = isNavigationActive,
            isAreaSelectMode = isAreaSelectMode,
            onAreaSelectToggle = onAreaSelectToggle,
            isLandscape = true,
            onNavStartClick = onNavStartClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    end = systemInsets.calculateEndPadding(LayoutDirection.Ltr) + 12.dp,
                    top = effectiveTopPadding + (if (isNavigationActive) 12.dp else 68.dp),
                )
                .zIndex(2f),
        )
    } else if (!isNavigationActive) {
        // 右上 (チップ列の直下): レイヤー切替ボタン + 回転時のみコンパス (Google Maps 配置)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = effectiveTopPadding + (if (isRoutePreviewActive) 12.dp else 122.dp), end = 14.dp)
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
                    color = if (currentVectorTilt > 15.0) Color(0xFF1A73E8) else Color(0xFF3C4043),
                )
            }
            // エリア選択ボタン (縦画面)
            Surface(
                shape = if (isAreaSelectMode) RoundedCornerShape(22.dp) else CircleShape,
                color = if (isAreaSelectMode) MaterialTheme.colorScheme.primary else Color.White,
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
                        tint = if (isAreaSelectMode) Color.White else Color(0xFF3C4043),
                    )
                }
            }
            val safeBearing = if (mapBearing.isNaN()) 0f else mapBearing
            AnimatedVisibility(
                visible = kotlin.math.abs(safeBearing) > 2.0f,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                FloatingCircleButton(
                    onClick = onHeadingUpToggle,
                    contentDescription = "北を上に",
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_compass),
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(-safeBearing),
                        tint = Color(0xFFD32F2F),
                    )
                }
            }
            GoogleZoomPill(
                onZoomIn = onZoomInAction,
                onZoomOut = onZoomOutAction,
                canZoomIn = canZoomIn,
                canZoomOut = canZoomOut,
            )
        }

        // 右下 (ボトムシートの直上): 現在地ボタン (白丸) + 経路検索FAB (ティール角丸四角)
        // 下部の速度・GPX 記録の行が出ている間は、その分だけ持ち上げて重ならないようにする
        val bottomSheetOffset = systemInsets.calculateBottomPadding() + 176.dp + (if (bottomHudVisible) 68.dp else 0.dp)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = bottomSheetOffset)
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ルートプレビュー中は下のシートがこの位置を覆うので出さない
            if (!isRoutePreviewActive) {
                GoogleGpsButton(
                    following = followLocation,
                    onClick = onTargetClick,
                )
                NavStartFAB(
                    onClick = onNavStartClick,
                )
            }
        }
    } else {
        // 案内中も地図上部の右側に主要操作を残し、Google Mapsのナビ画面に近い操作配置にする。
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = effectiveTopPadding + 104.dp, end = 14.dp)
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FloatingCircleButton(
                onClick = onHeadingUpToggle,
                contentDescription = "コンパス・北向き切替",
                modifier = Modifier.size(42.dp),
            ) {
                val safeBearing = if (mapBearing.isNaN()) 0f else mapBearing
                Icon(
                    painter = painterResource(R.drawable.ic_lucide_compass),
                    contentDescription = null,
                    modifier = Modifier.size(21.dp).rotate(-safeBearing),
                    tint = Color(0xFFD32F2F),
                )
            }
            GoogleZoomButton(
                iconRes = R.drawable.ic_ms_add,
                contentDescription = "拡大",
                onClick = onZoomInAction,
            )
            GoogleZoomButton(
                iconRes = R.drawable.ic_lucide_minus,
                contentDescription = "縮小",
                onClick = onZoomOutAction,
            )
            GoogleGpsButton(
                following = followLocation,
                onClick = onTargetClick,
            )
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
        GoogleLayerButton(
            onClick = { if (selector != null) expanded = true else onFallbackToggle() },
            label = fallbackLabel,
        )
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
