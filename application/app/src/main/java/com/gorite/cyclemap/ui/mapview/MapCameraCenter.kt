package com.gorite.cyclemap.ui.mapview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 地図のカメラが止まった位置 (緯度・経度)。`VectorMapView` が更新し、画面下の地名表示が
 * 「現在地に追従していないとき (地図を動かして見ているとき)」に、表示中の場所の地名を引くのに使う。
 */
internal object MapCameraCenter {
    private val _center = MutableStateFlow<Pair<Double, Double>?>(null)
    val center: StateFlow<Pair<Double, Double>?> = _center

    /** ベクター地図の直近のズーム。レイヤー切替でラスター地図を出すときに縮尺を引き継ぐ。 */
    @Volatile
    var lastVectorZoom: Double? = null

    fun update(latitude: Double, longitude: Double) {
        if (latitude.isFinite() && longitude.isFinite()) _center.value = latitude to longitude
    }
}
