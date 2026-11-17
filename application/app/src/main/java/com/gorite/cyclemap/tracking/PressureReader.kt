package com.gorite.cyclemap.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 気圧計 (TYPE_PRESSURE) の最新値。サイコン画面の獲得標高を、GPS の高度より滑らかに出すために使う。
 * 画面に出している間だけ [start] し、電池を食わないよう [stop] で止める。気圧計が無い端末では [latestHpa] は常に null。
 */
class PressureReader(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    @Volatile
    var latestHpa: Double? = null
        private set

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        manager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        manager?.unregisterListener(this)
        latestHpa = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val hpa = event.values.firstOrNull()?.toDouble() ?: return
        // 800〜1100 hPa の外は異常値として捨てる (標高 -700 m〜2000 m に相当)。
        if (hpa in 800.0..1100.0) latestHpa = hpa
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
