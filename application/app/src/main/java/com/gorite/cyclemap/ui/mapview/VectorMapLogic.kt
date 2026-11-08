package com.gorite.cyclemap.ui.mapview

import com.gorite.cyclemap.power.CameraMotionPolicy

/** POI を取得する緯度経度の矩形。 */
internal data class PoiFetchBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
)

/**
 * 表示領域から POI の取得範囲を決める (VectorMapView の triggerFetchPois から抽出。挙動は同じ)。
 * 表示領域の幅が十分あるときは上下左右に 20% の余白を足し、初回レイアウト前など表示領域が
 * 未確定 (幅 0.002 度未満) のときは、ズームに応じた最小幅を使って中心基準で確保する。
 */
internal fun poiFetchBounds(
    centerLat: Double,
    centerLon: Double,
    zoom: Double,
    south: Double,
    north: Double,
    west: Double,
    east: Double,
): PoiFetchBounds {
    val minZoomSpan = (360.0 / Math.pow(2.0, zoom.coerceIn(5.0, 20.0))).coerceIn(0.008, 2.0)
    val rawLatSpan = north - south
    val rawLonSpan = east - west
    val latSpan = rawLatSpan.coerceAtLeast(minZoomSpan)
    val lonSpan = rawLonSpan.coerceAtLeast(minZoomSpan)
    val halfLat = latSpan * 0.60
    val halfLon = lonSpan * 0.60
    return PoiFetchBounds(
        minLat = if (rawLatSpan >= 0.002) south - rawLatSpan * 0.20 else centerLat - halfLat,
        minLon = if (rawLonSpan >= 0.002) west - rawLonSpan * 0.20 else centerLon - halfLon,
        maxLat = if (rawLatSpan >= 0.002) north + rawLatSpan * 0.20 else centerLat + halfLat,
        maxLon = if (rawLonSpan >= 0.002) east + rawLonSpan * 0.20 else centerLon + halfLon,
    )
}

/** カメラ停止時に POI の再取得を省くか。前回の取得位置から 20m 未満かつズーム差 0.2 未満なら省く。 */
internal fun shouldSkipPoiRefetch(
    lastLat: Double?,
    lastLon: Double?,
    lastZoom: Double,
    targetLat: Double?,
    targetLon: Double?,
    zoom: Double,
): Boolean {
    if (lastLat == null || lastLon == null || targetLat == null || targetLon == null) return false
    return CameraMotionPolicy.distanceMeters(lastLat, lastLon, targetLat, targetLon) < 20.0 &&
        kotlin.math.abs(zoom - lastZoom) < 0.2
}
