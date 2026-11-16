package com.gorite.cyclemap.ui.layout

import com.gorite.cyclemap.ui.theme.CycleMapTheme
import com.gorite.cyclemap.*
import com.gorite.cyclemap.utils.*
import com.gorite.cyclemap.navigation.*
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.gorite.cyclemap.routing.NavigationStats
import com.gorite.cyclemap.routing.RoutePoint
import com.gorite.cyclemap.routing.RoutePreference
import com.gorite.cyclemap.routing.RouteProgress
import com.gorite.cyclemap.tracking.LocationTrackingService
import com.gorite.cyclemap.ui.cycling.ElevationSample
import com.gorite.cyclemap.ui.cycling.GoogleNavFooter
import com.gorite.cyclemap.ui.cycling.GoogleNavTab
import com.gorite.cyclemap.ui.cycling.GoogleNavTabBar
import com.gorite.cyclemap.ui.cycling.LocationSummarySheet
import com.gorite.cyclemap.ui.cycling.RideMetrics
import com.gorite.cyclemap.ui.cycling.RouteInfoCardContent
import com.gorite.cyclemap.ui.cycling.buildRouteElevationProfile
import com.gorite.cyclemap.ui.cycling.elevationAt
import com.gorite.cyclemap.ui.cycling.elevationGain
import com.gorite.cyclemap.ui.cycling.forwardGradeAt
import com.gorite.cyclemap.ui.cycling.gradeAt
import com.gorite.cyclemap.ui.theme.Motion
import org.osmdroid.util.GeoPoint

/** 地図下部の速度表示 + GPX 記録ボタンの行を出す条件。右下のボタン群が重ならないよう MapControlsOverlay も参照する。 */
internal fun isBottomHudVisible(isRecording: Boolean, speedKmh: Double): Boolean = isRecording || speedKmh >= 1.5

@Composable
internal fun BoxScope.MapBottomOverlays(
    isNavigationActive: Boolean,
    routeSummary: RouteSummary?,
    navStats: NavigationStats?,
    speedKmh: Double,
    isRecording: Boolean,
    gpxPointCount: Int,
    hasLocationPermission: Boolean,
    onWarningMessage: (String) -> Unit,
    onStopNavigation: () -> Unit,
    onClearRoute: () -> Unit,
    onShowLicense: () -> Unit,
    isLandscape: Boolean,
    systemInsets: PaddingValues,
    isCalculatingRoute: Boolean,
    isSwitchingGraph: Boolean,
    effectiveAnimationEnabled: Boolean,
    destination: GeoPoint?,
    waypoints: List<GeoPoint>,
    onMoveWaypoint: (Int, Int) -> Unit,
    onRemoveWaypoint: (Int) -> Unit,
    realElevProfile: List<ElevationSample>,
    navigationRoute: List<RoutePoint>,
    navigationProgress: RouteProgress?,
    onStartNavigation: () -> Unit,
    downloadCorridorTiles: () -> Unit,
    exportPlannedRoute: () -> Unit,
    corridorTileProgressText: String?,
    selectedRoutePreference: RoutePreference,
    onRoutePreferenceChange: (RoutePreference) -> Unit,
    routeChoices: List<com.gorite.cyclemap.ui.cycling.RouteChoice> = emptyList(),
    selectedRouteChoice: Int = 0,
    onSelectRouteChoice: (Int) -> Unit = {},
    isArrived: Boolean,
    locationLabel: String,
    googleNavTab: GoogleNavTab,
    onTabSelected: (GoogleNavTab) -> Unit,
    onExploreClick: () -> Unit,
    onSavedClick: () -> Unit,
    onRecordHistoryClick: () -> Unit,
    context: Context,
    isVoiceMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    weatherUiState: com.gorite.cyclemap.weather.WeatherUiState = com.gorite.cyclemap.weather.WeatherUiState.Unavailable,
    onRefreshWeather: () -> Unit = {},
    /** 待ち受けの盤面から「地図を広げる」で来たとき、盤面へ戻す。null なら従来の地名シートとタブを出す。 */
    onBackToDashboard: (() -> Unit)? = null,
    /** ルート確認シートの高さ (px、画面下端からシート上端まで)。シートが無いときは 0。 */
    onRoutePreviewSheetHeightChanged: (Int) -> Unit = {},
) {
    val isRoutePreviewActive = !isNavigationActive && routeSummary != null && !isArrived
    LaunchedEffect(isRoutePreviewActive) {
        if (!isRoutePreviewActive) {
            onRoutePreviewSheetHeightChanged(0)
        }
    }
    if (isNavigationActive && routeSummary != null && !isLandscape) {
        // 縦画面: 数値パネル (速度・残り・勾配・先の標高・到着・電池)
        val navProfile = if (realElevProfile.isNotEmpty()) realElevProfile else remember(routeSummary, navigationRoute) {
            buildRouteElevationProfile(navigationRoute.map { it.latitude to it.longitude }, routeSummary.distanceMeters)
        }
        val progressM = navigationProgress?.distanceFromStartMeters ?: 0.0
        val remainingM = navStats?.remainingMeters ?: (routeSummary.distanceMeters - progressM).coerceAtLeast(0.0)
        val panelData = remember(navProfile, progressM, remainingM, speedKmh.roundToInt(), navStats?.etaEpochMillis, navStats?.durationRemainingSeconds) {
            com.gorite.cyclemap.ui.map.buildNavPanelData(
                speedKmh = speedKmh,
                remainingMeters = remainingM,
                profile = navProfile,
                progressM = progressM,
                // 案内開始直後などで統計が未算出のときも、標準の巡航速度 (15km/h) で見込みを出す
                etaEpochMillis = navStats?.etaEpochMillis ?: (System.currentTimeMillis() + (remainingM / PRE_START_ASSUMED_SPEED_MPS * 1000.0).toLong()),
                remainingSeconds = navStats?.durationRemainingSeconds?.takeIf { it.isFinite() } ?: (remainingM / PRE_START_ASSUMED_SPEED_MPS),
            )
        }
        com.gorite.cyclemap.ui.map.NavBottomPanelC(
            data = panelData,
            batteryPercent = com.gorite.cyclemap.ui.map.rememberBatteryPercent(),
            isMuted = isVoiceMuted,
            onToggleMute = onToggleMute,
            onStopNavigation = onStopNavigation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f),
            bottomInset = systemInsets.calculateBottomPadding(),
        )
    } else if (isNavigationActive && routeSummary != null) {
        // 横画面: コンパクトなフッター (GoogleNavFooterView)
        com.gorite.cyclemap.ui.map.GoogleNavFooterView(
            eta = navStats?.let {
                val nowMs = System.currentTimeMillis()
                com.gorite.cyclemap.contract.EtaInfo(
                    remainingDistanceMeters = it.remainingMeters,
                    remainingSeconds = it.durationRemainingSeconds,
                    arrivalEpochMillis = nowMs + (it.durationRemainingSeconds * 1000).toLong(),
                    currentSpeedKmh = speedKmh
                )
            },
            remainingDistanceMeters = routeSummary.distanceMeters,
            remainingSeconds = routeSummary.distanceMeters / 4.2,
            isMuted = isVoiceMuted,
            onToggleMute = onToggleMute,
            onStopNavigation = onStopNavigation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp)
                .padding(bottom = systemInsets.calculateBottomPadding() + 8.dp)
                .zIndex(2f),
        )
    } else if (routeSummary != null && !isArrived) {
        // ルート案内前プレビューモード (テーマ追従 SCR-10)
        DisposableEffect(Unit) {
            onDispose { onRoutePreviewSheetHeightChanged(0) }
        }
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(top = 12.dp)
                .onSizeChanged { onRoutePreviewSheetHeightChanged(it.height) }
                .padding(bottom = systemInsets.calculateBottomPadding())
                .zIndex(2f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // ドラッグハンドル
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                )
                Spacer(modifier = Modifier.height(10.dp))

                // ルート探索中プログレス表示
                AnimatedVisibility(
                    visible = isCalculatingRoute || isSwitchingGraph,
                    enter = Motion.bannerEnter(effectiveAnimationEnabled),
                    exit = Motion.bannerExit(effectiveAnimationEnabled),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (isSwitchingGraph) "県データグラフを切替・読み込み中…" else "最適サイクリングルートを探索中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (waypoints.isNotEmpty()) {
                            com.gorite.cyclemap.ui.cycling.WaypointList(
                                waypoints = waypoints,
                                isEnabled = false,
                                onMove = onMoveWaypoint,
                                onRemove = onRemoveWaypoint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                // ルートサマリー
                AnimatedVisibility(
                    visible = !isCalculatingRoute && !isSwitchingGraph,
                    enter = Motion.bannerEnter(effectiveAnimationEnabled),
                    exit = Motion.bannerExit(effectiveAnimationEnabled),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val summary = routeSummary
                        val elevProfile = if (realElevProfile.isNotEmpty()) realElevProfile else remember(summary, navigationRoute) {
                            buildRouteElevationProfile(navigationRoute.map { it.latitude to it.longitude }, summary.distanceMeters)
                        }
                        val progressM = navigationProgress?.distanceFromStartMeters
                        val rideMetrics = navStats?.let { stats ->
                            RideMetrics(
                                speedKmh = speedKmh,
                                totalDistanceM = summary.distanceMeters,
                                remainingM = stats.remainingMeters,
                                elapsedS = stats.elapsedSeconds,
                                remainingS = stats.durationRemainingSeconds.takeIf { it.isFinite() },
                                averageKmh = stats.effectiveSpeedMps * 3.6,
                                elevationGainM = progressM?.let { elevationGain(elevProfile, it) },
                                currentElevationM = progressM?.let { elevationAt(elevProfile, it) },
                                gradePct = progressM?.let { gradeAt(elevProfile, it) },
                                forwardGradePct = progressM?.let { forwardGradeAt(elevProfile, it) },
                            )
                        } ?: RideMetrics(
                            speedKmh = speedKmh,
                            totalDistanceM = summary.distanceMeters,
                            remainingM = summary.distanceMeters,
                            elapsedS = 0.0,
                            remainingS = summary.distanceMeters / PRE_START_ASSUMED_SPEED_MPS,
                            averageKmh = PRE_START_ASSUMED_SPEED_MPS * 3.6,
                            elevationGainM = elevationGain(elevProfile, summary.distanceMeters),
                            currentElevationM = elevationAt(elevProfile, 0.0),
                            gradePct = gradeAt(elevProfile, 0.0),
                            forwardGradePct = forwardGradeAt(elevProfile, 0.0),
                        )
                        RouteInfoCardContent(
                            metrics = rideMetrics,
                            profile = elevProfile,
                            totalDistanceM = summary.distanceMeters,
                            progressDistanceM = progressM,
                            started = false,
                            onStart = onStartNavigation,
                            onClearRoute = onClearRoute,
                            modifier = Modifier.padding(bottom = 4.dp),
                            onDownloadCorridorTiles = downloadCorridorTiles,
                            onExportGpx = exportPlannedRoute,
                            tileDownloadStatus = corridorTileProgressText,
                            selectedPreference = selectedRoutePreference,
                            onPreferenceChange = onRoutePreferenceChange,
                            routeChoices = routeChoices,
                            selectedRouteChoice = selectedRouteChoice,
                            onSelectRouteChoice = onSelectRouteChoice,
                            waypoints = waypoints,
                            isCalculatingRoute = isCalculatingRoute || isSwitchingGraph,
                            onMoveWaypoint = onMoveWaypoint,
                            onRemoveWaypoint = onRemoveWaypoint,
                        )
                    }
                }
            }
        }
    } else {
        // 通常探索時: 下部スタック
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    bottom = if (isLandscape) {
                        systemInsets.calculateBottomPadding() + 8.dp
                    } else 0.dp,
                )
                .zIndex(2f),
        ) {
            // 到着カード
            AnimatedVisibility(
                visible = isArrived,
                enter = Motion.bannerEnter(effectiveAnimationEnabled),
                exit = Motion.bannerExit(effectiveAnimationEnabled),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CycleMapTheme.palette.successContainer),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "目的地に到着しました",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CycleMapTheme.palette.success,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = onClearRoute,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CycleMapTheme.palette.success),
                        ) {
                            Text("終了", color = CycleMapTheme.palette.onSuccess)
                        }
                    }
                }
            }

            // Main HUD: Speedometer & GPX Controls (走行中またはGPX記録中のみ表示)
            if (isBottomHudVisible(isRecording, speedKmh)) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: Large Speedometer (ルート未設定時のみ)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "%.1f".format(speedKmh.coerceAtLeast(0.0)),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "km/h",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }

                // Right: GPX Controls
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isRecording) CycleMapTheme.palette.danger else MaterialTheme.colorScheme.surface,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .clickable {
                                if (!hasLocationPermission) {
                                    onWarningMessage("GPX記録には位置情報の許可が必要です")
                                } else if (isRecording) {
                                    LocationTrackingService.stopRecording(context)
                                } else {
                                    LocationTrackingService.startRecording(context)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isRecording) CycleMapTheme.palette.onDanger else CycleMapTheme.palette.danger),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) "記録中 (${gpxPointCount}pt)" else "GPX記録",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) CycleMapTheme.palette.onDanger else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (!isLandscape && onBackToDashboard != null) {
                // 地図を広げた状態: 地名シートとタブの代わりに、盤面へ戻るボタンだけを親指の位置に置く
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp + systemInsets.calculateBottomPadding()),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(onClick = onBackToDashboard),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "盤面に戻る",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            } else if (!isLandscape) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 5. 地名・天候サマリーシート (SCR-01, SCR-02)
                    LocationSummarySheet(
                        locationName = locationLabel.ifEmpty { "現在地" },
                        weatherUiState = weatherUiState,
                        onRefreshWeather = onRefreshWeather,
                        onClick = onExploreClick,
                    )

                    // 6. 3タブナビゲーションバー (スポット / 保存済み / 記録)
                    GoogleNavTabBar(
                        selectedTab = googleNavTab,
                        onTabSelected = { tab ->
                            onTabSelected(tab)
                            when (tab) {
                                GoogleNavTab.EXPLORE -> onExploreClick()
                                GoogleNavTab.SAVED -> onSavedClick()
                                GoogleNavTab.RECORD -> onRecordHistoryClick()
                            }
                        },
                    )
                }
            }
        }
    }
}
