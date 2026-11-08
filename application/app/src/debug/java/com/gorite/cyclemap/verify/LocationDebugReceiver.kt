package com.gorite.cyclemap.verify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log

/**
 * debug ビルド専用: adb から模擬の現在地を流す (走らずに案内・リルートを確かめる)。端末の設定は変えない。
 *
 *   adb shell am broadcast -a com.gorite.cyclemap.test.SIMLOC -p com.gorite.cyclemap.test \
 *       --ed lat 34.3668 --ed lon 132.3780 [--ef speed 5.0] [--ef bearing 90] [--ef accuracy 5]
 */
class LocationDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SIMLOC) return
        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            Log.w(TAG, "SIMLOC ignored: lat/lon are required")
            return
        }
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat
            longitude = lon
            accuracy = intent.getFloatExtra("accuracy", 5f)
            speed = intent.getFloatExtra("speed", 5f)
            if (intent.hasExtra("bearing")) bearing = intent.getFloatExtra("bearing", 0f)
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        Log.i(TAG, "SIMLOC $lat,$lon posted=${SimulatedLocationBus.post(location)}")
    }

    companion object {
        const val ACTION_SIMLOC = "com.gorite.cyclemap.test.SIMLOC"
        private const val TAG = "CycleMapVerify"
    }
}
