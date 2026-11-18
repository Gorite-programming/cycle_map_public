package com.gorite.cyclemap.navigation

import android.location.Location
import org.osmdroid.util.GeoPoint

internal const val WAITING_FOR_START_POINT_MESSAGE =
    "現在地を取得中です。地図を長押しして出発地を選べます"

/**
 * 経路計算の出発地を決める。
 * 手動で置いた出発地 (startMarker) を最優先し、無ければ現在地を使う。
 * どちらも無いときは山口市役所等の仮座標にすり替えず null を返す。
 */
internal fun resolveStartPoint(
    startMarkerPosition: GeoPoint?,
    currentLocation: GeoPoint?,
): GeoPoint? = startMarkerPosition ?: currentLocation

internal fun resolveStartPoint(
    startMarkerPosition: GeoPoint?,
    currentLatitude: Double?,
    currentLongitude: Double?,
): GeoPoint? = startMarkerPosition
    ?: if (currentLatitude != null && currentLongitude != null) {
        GeoPoint(currentLatitude, currentLongitude)
    } else {
        null
    }

internal fun resolveStartPointFromLocation(
    startMarkerPosition: GeoPoint?,
    currentLocation: Location?,
): GeoPoint? = resolveStartPoint(
    startMarkerPosition = startMarkerPosition,
    currentLatitude = currentLocation?.latitude,
    currentLongitude = currentLocation?.longitude,
)
