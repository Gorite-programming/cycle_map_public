package com.gorite.cyclemap.ui.map

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.gorite.cyclemap.ui.cycling.NearbySpot
import com.gorite.cyclemap.ui.cycling.QuickSpotType
import com.gorite.cyclemap.ui.cycling.SpotQuickCategory

/*
 * 地図画面の状態のうち、画面の部品 (MapView・マーカー・オーバーレイなど) と結びつかない「データ」を
 * MapViewModel に持たせるための入れ物。
 *
 * 以前は rememberMapScreenState の中で remember { mutableStateOf(...) } として作っていたため、
 * 状態の持ち主が画面の組み立て (Composition) で、どこで変わるかを追いにくかった。
 * ViewModel に置くと、持ち主がはっきりし、アクティビティが作り直されても残る。
 *
 * 呼び出し側の書き方 (state.xxxState.value) を変えずに移せるよう、Compose の MutableState のまま持つ。
 * Context や View を参照する物はここに入れない (アクティビティより長く生きるのでリークする)。
 */

/** 検索・スポット一覧・POI の選択。 */
class SearchSessionState {
    val showDestinationSearch: MutableState<Boolean> = mutableStateOf(false)
    val selectedPoiSpot: MutableState<NearbySpot?> = mutableStateOf(null)

    val showSpotPanel: MutableState<Boolean> = mutableStateOf(false)
    val showFavoritesOnlyInSheet: MutableState<Boolean> = mutableStateOf(false)
    val spotCategory: MutableState<SpotQuickCategory?> = mutableStateOf(null)
    val spotResults: MutableState<List<NearbySpot>> = mutableStateOf(emptyList())
    val spotLoading: MutableState<Boolean> = mutableStateOf(false)
    val spotMessage: MutableState<String?> = mutableStateOf(null)

    val activeQuickSpotType: MutableState<QuickSpotType?> = mutableStateOf(null)
    val showQuickSpotSheet: MutableState<Boolean> = mutableStateOf(false)
    val quickSpotResults: MutableState<List<NearbySpot>> = mutableStateOf(emptyList())
    val quickSpotLoading: MutableState<Boolean> = mutableStateOf(false)
    val quickSpotMessage: MutableState<String?> = mutableStateOf(null)
}

/** 目的地・経由地・計算したルート (案内を始める前のデータ)。 */
internal class RouteSessionState {
    val destination: MutableState<org.osmdroid.util.GeoPoint?> = mutableStateOf(null)
    val waypoints: MutableState<List<org.osmdroid.util.GeoPoint>> = mutableStateOf(emptyList())
    val pendingSelectedSpot: MutableState<org.osmdroid.util.GeoPoint?> = mutableStateOf(null)
    val preference: MutableState<com.gorite.cyclemap.routing.RoutePreference> =
        mutableStateOf(com.gorite.cyclemap.routing.RoutePreference.RECOMMENDED)

    val summary: MutableState<com.gorite.cyclemap.utils.RouteSummary?> = mutableStateOf(null)
    val options: MutableState<List<com.gorite.cyclemap.contract.RouteOption>> = mutableStateOf(emptyList())
    val selectedIndex: MutableState<Int> = mutableStateOf(0)
    val points: MutableState<List<com.gorite.cyclemap.routing.RoutePoint>> = mutableStateOf(emptyList())
    val maneuvers: MutableState<List<com.gorite.cyclemap.routing.Maneuver>> = mutableStateOf(emptyList())
    val instructions: MutableState<List<com.gorite.cyclemap.routing.RouteInstruction>> = mutableStateOf(emptyList())
    val isCalculating: MutableState<Boolean> = mutableStateOf(false)
    val elevationProfile: MutableState<List<com.gorite.cyclemap.ui.cycling.ElevationSample>> = mutableStateOf(emptyList())
    val crossRouteElevation: MutableState<com.gorite.cyclemap.navigation.CrossRouteElevationData?> = mutableStateOf(null)
}

/**
 * アプリが裏で終了させられても戻すための、ルートの最小限の記録。
 * ルートそのもの (点の列) は大きいので保存せず、目的地・経由地・好みから計算し直す。
 */
internal data class RouteSessionSnapshot(
    val destinationLat: Double,
    val destinationLon: Double,
    /** 経由地を [緯度0, 経度0, 緯度1, 経度1, ...] の順に並べたもの。 */
    val waypointLatLons: List<Double>,
    val preferenceName: String,
    /** 案内中だったか。true なら、ルートを計算し直したあと案内も再開する。 */
    val wasNavigating: Boolean,
) {
    val destination: org.osmdroid.util.GeoPoint get() = org.osmdroid.util.GeoPoint(destinationLat, destinationLon)
    val waypoints: List<org.osmdroid.util.GeoPoint>
        get() = waypointLatLons.chunked(2).filter { it.size == 2 }.map { org.osmdroid.util.GeoPoint(it[0], it[1]) }
    val preference: com.gorite.cyclemap.routing.RoutePreference
        get() = com.gorite.cyclemap.routing.RoutePreference.entries.firstOrNull { it.name == preferenceName }
            ?: com.gorite.cyclemap.routing.RoutePreference.RECOMMENDED

    companion object {
        /** 目的地が無ければ記録しない (戻すものが無い)。 */
        fun capture(route: RouteSessionState, isNavigating: Boolean): RouteSessionSnapshot? {
            val dest = route.destination.value ?: return null
            return RouteSessionSnapshot(
                destinationLat = dest.latitude,
                destinationLon = dest.longitude,
                waypointLatLons = route.waypoints.value.flatMap { listOf(it.latitude, it.longitude) },
                preferenceName = route.preference.value.name,
                wasNavigating = isNavigating,
            )
        }
    }
}
