package com.gorite.cyclemap.verify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * debug ビルド専用: adb から地図カメラを任意の地点・ズームへ動かす。
 *
 *   adb shell am broadcast -a com.gorite.cyclemap.test.CAMERA --ed lat 34.3945 --ed lon 132.4553 --ed zoom 15
 *   (任意: --ed tilt 60 --ed bearing 90)
 *
 * 追従は解除され、その地点の県のタイル・POI に切り替わる。
 */
class CameraDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CAMERA) return
        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            Log.w(TAG, "CAMERA ignored: lat/lon are required (--ed lat <deg> --ed lon <deg>)")
            return
        }
        val request = VerificationCameraRequest(
            latitude = lat,
            longitude = lon,
            zoom = intent.getDoubleExtra("zoom", 15.0),
            tilt = intent.getDoubleExtra("tilt", 0.0),
            bearing = intent.getDoubleExtra("bearing", 0.0),
        )
        Log.i(TAG, "CAMERA $request posted=${VerificationCameraBus.post(request)}")
    }

    companion object {
        const val ACTION_CAMERA = "com.gorite.cyclemap.test.CAMERA"
        private const val TAG = "CycleMapVerify"
    }
}
