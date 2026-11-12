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
