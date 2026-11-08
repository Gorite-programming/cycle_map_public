package com.gorite.cyclemap.ui.mapview

import android.view.Choreographer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMap

/**
 * ベクター地図の +/- ボタン用ズーム。毎フレーム moveCamera(zoomTo) で進めるので、
 * 案内中の追従カメラ (毎フレーム moveCamera) と打ち消し合わない。
 */
internal class MapZoomAnimator(private val getMap: () -> MapLibreMap?) {
    private val follower = ZoomFollower()
    private var running = false
    private var lastNanos = 0L
    private val callback = Choreographer.FrameCallback { nanos -> onFrame(nanos) }

    fun zoomBy(delta: Double) {
        val map = getMap() ?: return
        follower.nudge(delta, map.cameraPosition.zoom, map.minZoomLevel, map.maxZoomLevel, running)
        if (!running) {
            running = true
            lastNanos = 0L
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    private fun onFrame(nanos: Long) {
        val map = getMap()
        if (!running || map == null) {
            running = false
            return
        }
        val dt = if (lastNanos == 0L) 1f / 60f else ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        lastNanos = nanos
        val z = follower.step(dt)
        if (z.isFinite()) map.moveCamera(CameraUpdateFactory.zoomTo(z))
        if (follower.isSettled) {
            running = false
        } else {
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }
}
