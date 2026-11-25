package com.gorite.cyclemap.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import com.gorite.cyclemap.navigation.LocationFixPolicy
import android.os.SystemClock
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.gorite.cyclemap.R
import com.gorite.cyclemap.utils.hasLocationPermission
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns fused location for the whole app. GPX recording is an optional consumer of
 * the same stream and does not start or stop the location provider.
 */
class LocationTrackingService : Service() {
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val listeners = CopyOnWriteArrayList<(Location) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(GpsSignalStatus) -> Unit>()
    private val binder = LocalBinder()
    private var recorder: GpxRecorder? = null
    private val _recoveryPending = kotlinx.coroutines.flow.MutableStateFlow(false)
    val recoveryPendingState: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _recoveryPending.asStateFlow()
    var recoveryPending: Boolean
        get() = _recoveryPending.value
        private set(value) { _recoveryPending.value = value }
    @Volatile private var destroyed = false
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val saveCoordinator = GpxSaveCoordinator(
        dispatchIo = { saveExecutor.execute(it) },
        dispatchCompletion = { if (!destroyed) healthHandler.post(it) },
        onStopped = {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        },
    )
    private val saving: Boolean get() = saveCoordinator.saving
    private var tracking = false
    private var previousLocation: Location? = null
    private var smoothedSpeed = 0.0
    private val qualityGate = LocationQualityGate()
    @Volatile private var diagLog: LocationDiagLog? = null

    private val gpsHealthMonitor = GpsHealthMonitor()
    private val healthHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (tracking) {
                recorder?.flushIfDue()
                val newStatus = gpsHealthMonitor.tick()
                if (newStatus != lastStatus) {
                    notifyStatus(newStatus)
                }
                healthHandler.postDelayed(this, 1_000L)
            }
        }
    }

    @Volatile var lastStatus: GpsSignalStatus = GpsSignalStatus.HEALTHY
        private set

    @Volatile var recording: Boolean = false
        private set

    @Volatile var lastLocation: Location? = null
        private set

    val recordedPoints: Int get() = recorder?.pointCount ?: 0

    inner class LocalBinder : Binder() {
        val service: LocationTrackingService get() = this@LocationTrackingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restorePendingRecording()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TRACKING -> stopTracking()
            ACTION_START_RECORDING -> {
                if (!ensureTracking()) return START_NOT_STICKY
                startRecording()
            }
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_PAUSE_RECORDING -> { if (!saving) recorder?.pause() }
            ACTION_RESUME_RECORDING -> {
                if (!ensureTracking()) return START_NOT_STICKY
                if (saving) return START_STICKY
                recoveryPending = false
                recorder?.resume()
                recording = recorder != null
                if (recording) sendBroadcast(Intent(ACTION_RECORDING_STARTED).setPackage(packageName))
            }
            else -> if (!ensureTracking()) return START_NOT_STICKY
        }
        return START_STICKY
    }

    fun addListener(listener: (Location) -> Unit) {
        listeners += listener
        lastLocation?.takeIf { LocationFixPolicy.isFresh(it.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos()) }?.let(listener)
    }

    fun removeListener(listener: (Location) -> Unit) {
        listeners -= listener
    }

    fun addStatusListener(listener: (GpsSignalStatus) -> Unit) {
        statusListeners += listener
        listener(lastStatus)
    }

    fun removeStatusListener(listener: (GpsSignalStatus) -> Unit) {
        statusListeners -= listener
    }

    private fun notifyStatus(status: GpsSignalStatus) {
        lastStatus = status
        statusListeners.forEach { it(status) }
    }

    private fun ensureTracking(): Boolean {
        if (!promoteToForeground()) {
            stopSelf()
            return false
        }
        if (!tracking && !startTracking()) return false
        saveCoordinator.resumeTracking()
        return true
    }

    private fun promoteToForeground(): Boolean {
        if (!hasLocationPermission()) return false
        return try {
            startForeground(NOTIFICATION_ID, trackingNotification())
            true
        } catch (se: SecurityException) {
            android.util.Log.e("CycleMapTracking", "Cannot start location foreground service", se)
            false
        }
    }

    // ensureTracking checks location permission when promoting the service.
    @SuppressLint("MissingPermission")
    private fun startTracking(): Boolean {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(250L)
            .setMaxUpdateDelayMillis(1_000L)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            fusedClient.lastLocation.addOnSuccessListener { last ->
                if (last != null) considerLocation(last)
            }
        } catch (se: SecurityException) {
            android.util.Log.e("CycleMapTracking", "SecurityException requesting location updates", se)
            stopSelf()
            return false
        }
        tracking = true
        gpsHealthMonitor.reset()
        healthHandler.removeCallbacks(healthCheckRunnable)
        healthHandler.postDelayed(healthCheckRunnable, 1_000L)
        return true
    }

    private fun restorePendingRecording() {
        val dir = File(filesDir, "gpx-journals")
        journalsInRestoreOrder(dir.listFiles()?.toList().orEmpty()).forEach { file ->
            val journal = GpxJournal(file)
            val recovery = runCatching { journal.restore() }.getOrNull()
            if (recovery != null) {
                recorder = GpxRecorder(journal, recovery, RECORDING_FLUSH_POLICY, SystemClock::elapsedRealtime)
                    .also { it.pause() }
                recoveryPending = true
                return
            }
        }
    }

    private fun startRecording() {
        if (recoveryPending || saving) return
        if (recorder != null) {
            if (recorder?.paused == true) recorder?.resume()
            return
        }
        recorder = GpxRecorder(
            GpxJournal(File(filesDir, "gpx-journals/${java.util.UUID.randomUUID()}.journal")),
            flushPolicy = RECORDING_FLUSH_POLICY,
            clock = SystemClock::elapsedRealtime,
        )
        recording = true
        openDiagLog()
        promoteToForeground()
        sendBroadcast(Intent(ACTION_RECORDING_STARTED).setPackage(packageName))
    }

    private fun stopRecording() {
        if (saving) return
        val snapshot = recorder ?: return
        snapshot.pause()
        recording = false
        closeDiagLog()
        recoveryPending = false
        val outputDir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "CycleMap/gpx")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())
        val points = snapshot.pointCount

        if (points == 0) {
            snapshot.deleteJournal()
            recorder = null
            restorePendingRecording()
            sendBroadcast(Intent(ACTION_RECORDING_EMPTY).setPackage(packageName))
            if (tracking) promoteToForeground()
            return
        }

        var saved = false
        saveCoordinator.save(
            write = {
                val output = snapshot.writeToFile(File(outputDir, "cyclemap_${timestamp}_${snapshot.startedAt}.gpx"))
                saved = true
                snapshot.deleteJournal()
                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString(KEY_LAST_FILE, output.absolutePath).apply()
                sendBroadcast(
                    Intent(ACTION_RECORDING_SAVED).setPackage(packageName)
                        .putExtra(EXTRA_PATH, output.absolutePath)
                        .putExtra(EXTRA_POINTS, points),
                )
            },
            onComplete = { result ->
                result.exceptionOrNull()?.let {
                    android.util.Log.e("CycleMapTracking", "Failed to save GPX file", it)
                }
                // On failure retain the in-memory points as well as the journal.
                if (snapshot === recorder) {
                    if (saved) {
                        recorder = null
                        restorePendingRecording()
                    } else {
                        recoveryPending = true
                        sendBroadcast(Intent(ACTION_RECORDING_SAVE_FAILED).setPackage(packageName))
                    }
                }
            },
        )

        if (tracking) promoteToForeground()
    }

    private fun stopTracking() {
        stopRecordingIfNeeded()
        healthHandler.removeCallbacks(healthCheckRunnable)
        fusedClient.removeLocationUpdates(callback)
        tracking = false
        previousLocation = null
        smoothedSpeed = 0.0
        qualityGate.reset()
        closeDiagLog()
        saveCoordinator.requestStop()
    }

    private fun stopRecordingIfNeeded() {
        if (recording) stopRecording()
    }

    override fun onDestroy() {
        recorder?.flush()
        healthHandler.removeCallbacks(healthCheckRunnable)
        fusedClient.removeLocationUpdates(callback)
        tracking = false
        previousLocation = null
        smoothedSpeed = 0.0
        qualityGate.reset()
        closeDiagLog()
        destroyed = true
        saveExecutor.shutdown()
        super.onDestroy()
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::considerLocation)
        }
    }

    /** GPX の記録中だけ、届いた測位のすべてを端末内の CSV に残す (位置のぶれの原因を後で調べるため)。 */
    private fun openDiagLog() {
        closeDiagLog()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "CycleMap/diag")
        diagLog = LocationDiagLog.open(File(dir, "fixes_$stamp.csv"))
    }

    private fun closeDiagLog() {
        diagLog?.close()
        diagLog = null
    }

    /** 停止中 (ドップラー速度が 0) の測位は、位置を動かさず、最後に採用した位置のまま速度 0 を通知する。軌跡・距離には足さない。 */
    private fun holdPositionWhileStationary(newLocation: Location) {
        val previous = previousLocation ?: return
        val held = Location(previous).apply {
            time = newLocation.time
            elapsedRealtimeNanos = newLocation.elapsedRealtimeNanos
            speed = 0f
        }
        smoothedSpeed = 0.0
        gpsHealthMonitor.onValidFix()
        notifyStatus(GpsSignalStatus.HEALTHY)
        previousLocation = held
        lastLocation = held
        listeners.forEach { it(held) }
    }

    private fun considerLocation(newLocation: Location) {
        if (!LocationFixPolicy.accepts(newLocation.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos(),
                lastLocation?.elapsedRealtimeNanos)) return
        // 精度・位置の飛び・停止中の往復を判定する (実走で、停止中に位置が 60〜70 m 往復して距離に約 460 m 加わった)。
        val verdict = qualityGate.evaluate(
            GateFix(
                latitude = newLocation.latitude,
                longitude = newLocation.longitude,
                elapsedNanos = newLocation.elapsedRealtimeNanos,
                accuracyMeters = if (newLocation.hasAccuracy()) newLocation.accuracy else null,
                speedMps = if (newLocation.hasSpeed()) newLocation.speed else null,
                speedAccuracyMps = if (newLocation.hasSpeedAccuracy()) newLocation.speedAccuracyMetersPerSecond else null,
            ),
        )
        diagLog?.append(
            DiagFix(
                elapsedMillis = newLocation.elapsedRealtimeNanos / 1_000_000L,
                epochMillis = newLocation.time,
                latitude = newLocation.latitude,
                longitude = newLocation.longitude,
                accuracyMeters = if (newLocation.hasAccuracy()) newLocation.accuracy else null,
                speedMps = if (newLocation.hasSpeed()) newLocation.speed else null,
                speedAccuracyMps = if (newLocation.hasSpeedAccuracy()) newLocation.speedAccuracyMetersPerSecond else null,
                bearingDegrees = if (newLocation.hasBearing()) newLocation.bearing else null,
                provider = newLocation.provider,
                satellites = newLocation.extras?.getInt("satellites", -1)?.takeIf { it >= 0 },
            ),
            verdict,
        )
        when (verdict) {
            GateVerdict.REJECT_INACCURATE -> {
                gpsHealthMonitor.onAccuracyDegraded()
                notifyStatus(gpsHealthMonitor.status)
                return
            }
            GateVerdict.REJECT_JUMP -> return
            GateVerdict.STATIONARY -> {
                holdPositionWhileStationary(newLocation)
                return
            }
            GateVerdict.ACCEPT -> Unit
        }

        val oldLocation = previousLocation
        var speed = 0.0
        val timeDiffMs = if (oldLocation != null) (newLocation.elapsedRealtimeNanos - oldLocation.elapsedRealtimeNanos) / 1_000_000L else 0L
        val isLostOrStale = oldLocation == null || timeDiffMs > 8_000L || timeDiffMs < 0L

        if (!isLostOrStale && oldLocation != null) {
            val elapsedSeconds = timeDiffMs / 1_000.0
            val distanceMeters = oldLocation.distanceTo(newLocation).toDouble()
            
            // 位置の飛び (速度の上限超え・ドップラーとの食い違い) は、品質ゲートが先に捨てている。

            // 1. Calculate raw speed
            val rawSpeed = when {
                newLocation.hasSpeed() -> newLocation.speed.toDouble()
                elapsedSeconds > 0.0 && distanceMeters >= 1.0 -> distanceMeters / elapsedSeconds
                else -> 0.0
            }

            // 2. Stationary detection (Noise gate)
            // BUG-50 fix: Do not compare distanceMeters directly against accuracy,
            // because at 15 km/h (4.2 m/fix) typical GPS accuracy (5-15 m) will constantly zero out speed.
            val isStationary = if (newLocation.hasSpeed()) {
                rawSpeed < 0.5
            } else {
                distanceMeters < 1.0 || rawSpeed < 0.5
            }
            val finalRawSpeed = if (isStationary) 0.0 else rawSpeed

            // 3. Exponential Moving Average Smoothing
            smoothedSpeed = smoothedSpeed * 0.7 + finalRawSpeed * 0.3
            speed = smoothedSpeed
        } else {
            // First location or recovering from GPS loss / gap
            val rawSpeed = if (newLocation.hasSpeed()) newLocation.speed.toDouble() else 0.0
            smoothedSpeed = if (rawSpeed < 0.5) 0.0 else rawSpeed
            speed = smoothedSpeed
        }

        // Override speed on the location object so all downstream consumers use the filtered/smoothed speed
        // 端末が速度を出さなかった測位にも平滑化した速度を入れるので、元に速度があったかを extras に残す。
        // サイコン (RideSession) は、速度の無い測位では自分で位置の変化から速度を推定する。
        val hadSensorSpeed = newLocation.hasSpeed()
        newLocation.speed = speed.toFloat()
        newLocation.extras = (newLocation.extras ?: android.os.Bundle()).apply { putBoolean(EXTRA_SENSOR_SPEED, hadSensorSpeed) }

        gpsHealthMonitor.onValidFix()
        notifyStatus(GpsSignalStatus.HEALTHY)
        previousLocation = newLocation
        lastLocation = newLocation
        if (recording) recorder?.addPoint(newLocation)
        listeners.forEach { it(newLocation) }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "CycleMap α location", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun trackingNotification(): Notification {
        val title = if (recording) "CycleMap α GPX記録中" else "CycleMap α 位置追跡中"
        val text = if (recording) "位置情報を記録しています" else "画面オフ中も現在地を更新します"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_current_location)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_PAUSE_RECORDING = "com.gorite.cyclemap.GPX_PAUSE"
        const val ACTION_RECORDING_SAVE_FAILED = "com.gorite.cyclemap.GPX_SAVE_FAILED"
        const val ACTION_RESUME_RECORDING = "com.gorite.cyclemap.GPX_RESUME"
        fun pauseRecording(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_PAUSE_RECORDING))
        }
        fun resumeRecording(context: Context) {
            requestRecording(context, ACTION_RESUME_RECORDING)
        }
        const val ACTION_START_TRACKING = "com.gorite.cyclemap.LOCATION_START"
        const val ACTION_STOP_TRACKING = "com.gorite.cyclemap.LOCATION_STOP"
        const val ACTION_START_RECORDING = "com.gorite.cyclemap.GPX_START"
        const val ACTION_STOP_RECORDING = "com.gorite.cyclemap.GPX_STOP"
        const val ACTION_RECORDING_STARTED = "com.gorite.cyclemap.GPX_STARTED"
        const val ACTION_RECORDING_SAVED = "com.gorite.cyclemap.GPX_SAVED"
        const val ACTION_RECORDING_EMPTY = "com.gorite.cyclemap.GPX_EMPTY"
        const val EXTRA_PATH = "path"
        const val EXTRA_POINTS = "points"
        private const val CHANNEL_ID = "cyclemap_location"
        private const val NOTIFICATION_ID = 4201
        private const val PREFERENCES = "gpx_recording"
        private const val KEY_LAST_FILE = "last_file"
        private val RECORDING_FLUSH_POLICY = GpxRecorder.FlushPolicy(maxPoints = 10, maxAgeMillis = 10_000L)

        fun startTracking(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_START_TRACKING),
            )
        }

        fun stopTracking(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP_TRACKING))
        }

        fun startRecording(context: Context) {
            requestRecording(context, ACTION_START_RECORDING)
        }

        private fun requestRecording(context: Context, action: String) {
            dispatchRecordingStart(
                hasLocationPermission = context.hasLocationPermission(),
                onDenied = {
                    Toast.makeText(
                        context,
                        "位置情報の権限が無いため記録を開始できません",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                startService = {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, LocationTrackingService::class.java).setAction(action),
                    )
                },
            )
        }

        fun stopRecording(context: Context) {
            context.startService(Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP_RECORDING))
        }

        fun bind(context: Context, connection: ServiceConnection) {
            context.bindService(Intent(context, LocationTrackingService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }
}

fun locationServiceConnection(onConnected: (LocationTrackingService) -> Unit): ServiceConnection =
    object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            onConnected((binder as LocationTrackingService.LocalBinder).service)
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

internal fun dispatchRecordingStart(
    hasLocationPermission: Boolean,
    onDenied: () -> Unit,
    startService: () -> Unit,
) {
    if (hasLocationPermission) startService() else onDenied()
}

/**
 * 復元するジャーナルの順番。新しく書かれたものから (「前回の走行記録」が先に出る)。
 * ファイル名はランダムな UUID なので、名前の順は時刻と関係ない。同じ時刻なら名前で決める。
 */
internal fun journalsInRestoreOrder(files: List<File>): List<File> =
    files.filter { it.extension == "journal" }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })

/** 測位に端末 (ドップラー) の速度があったか。LocationTrackingService が速度を上書きする前の値を残す。 */
internal const val EXTRA_SENSOR_SPEED = "com.gorite.cyclemap.sensorSpeed"

/** 端末が出した速度。サービスが平滑化して入れただけの速度なら null。印が無い測位は速度があればそのまま使う。 */
internal fun Location.sensorSpeedMpsOrNull(): Float? =
    if (hasSpeed() && extras?.getBoolean(EXTRA_SENSOR_SPEED, true) != false) speed else null
