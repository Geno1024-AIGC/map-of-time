package com.geno1024.ai.mapoftime.track

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.geno1024.ai.mapoftime.AppContainer
import com.geno1024.ai.mapoftime.MapOfTimeApp
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.core.TripMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records one trip with a location foreground service, so a long walk keeps
 * collecting fixes with the screen off.
 *
 * Writes are fire-and-forget onto a single-threaded lane; the analyzer is
 * queued on that same lane before the service stops, so the last fix is always
 * on disk before the trip is cut into legs.
 */
class TrackService : Service(), LocationListener {
    private lateinit var container: AppContainer
    private lateinit var locationManager: LocationManager

    private var tripId = 0L
    private var mode = TripMode.WALK
    private var seq = 0
    private val liveDistance = DistanceAccumulator()
    private var markCount = 0
    private var startedAt = 0L
    private val recentFixes = ArrayDeque<LatLon>()
    private var lastNotificationAt = 0L
    private var recording = false

    override fun onCreate() {
        super.onCreate()
        container = (application as MapOfTimeApp).container
        locationManager = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_MODE))
            ACTION_STOP -> stop()
            ACTION_MARK -> addMark()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun start(modeId: String?) {
        if (recording) return
        mode = TripMode.of(modeId)
        startedAt = System.currentTimeMillis()

        // Checked inline (not through a helper) so static analysis can see that
        // the location call below is guarded: the permission may have been
        // revoked between the UI request and this start intent.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Recorder.publish(
                RecordingState(
                    phase = RecordingPhase.IDLE,
                    error = getString(R.string.error_need_location),
                ),
            )
            stopSelf()
            return
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Recorder.publish(
                RecordingState(
                    phase = RecordingPhase.IDLE,
                    mode = mode,
                    error = getString(R.string.error_need_gps),
                ),
            )
            stopSelf()
            return
        }

        tripId = container.repository.createTrip(mode, startedAt)
        seq = 0
        liveDistance.reset()
        markCount = 0
        recentFixes.clear()
        recording = true

        RecordingNotification.ensureChannel(this)
        val initial = Recorder.state.value.copy(
            phase = RecordingPhase.RECORDING,
            tripId = tripId,
            mode = mode,
            startedAt = startedAt,
            pointCount = 0,
            distanceM = 0.0,
            marks = 0,
            error = null,
        )
        Recorder.publish(initial)
        startInForeground(initial)

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            MIN_TIME_MS,
            0f,
            this,
            Looper.getMainLooper(),
        )
    }

    private fun startInForeground(state: RecordingState) {
        val notification = RecordingNotification.build(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RecordingNotification.id(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(RecordingNotification.id(), notification)
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!recording) return
        if (!location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METERS) return

        val point = TrackPoint(
            t = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            at = LatLon(location.latitude, location.longitude),
            accuracy = location.accuracy.toDouble(),
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else null,
        )

        liveDistance.add(point.at, trustworthy = point.accuracy <= LIVE_ACCURACY_METERS)
        recentFixes.addLast(point.at)
        while (recentFixes.size > RECENT_FIXES_LIMIT) recentFixes.removeFirst()
        seq += 1
        val storedSeq = seq

        container.scope.launch(container.dbDispatcher) {
            container.repository.appendPoint(tripId, storedSeq, point)
            if (storedSeq % PROGRESS_EVERY == 0) {
                container.repository.updateProgress(tripId, liveDistance.meters, storedSeq)
            }
        }

        val state = Recorder.state.value.copy(
            pointCount = storedSeq,
            distanceM = liveDistance.meters,
            accuracyM = point.accuracy,
            lastFixAt = point.t,
            lastFix = point.at,
            recentFixes = recentFixes.toList(),
        )
        Recorder.publish(state)

        val now = System.currentTimeMillis()
        if (now - lastNotificationAt > NOTIFICATION_REFRESH_MS) {
            lastNotificationAt = now
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.notify(RecordingNotification.id(), RecordingNotification.build(this, state))
        }
    }

    private fun addMark() {
        if (!recording) return
        val at = System.currentTimeMillis()
        markCount += 1
        container.scope.launch(container.dbDispatcher) {
            container.repository.addMark(tripId, at)
        }
        Recorder.publish(Recorder.state.value.copy(marks = markCount))
    }

    private fun stop() {
        if (!recording) {
            Recorder.reset()
            stopSelf()
            return
        }
        recording = false
        locationManager.removeUpdates(this)
        val curvedTrip = tripId
        val fixes = seq

        Recorder.publish(
            Recorder.state.value.copy(phase = RecordingPhase.PROCESSING),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        container.scope.launch {
            withContext(container.dbDispatcher) {
                container.repository.updateProgress(curvedTrip, liveDistance.meters, fixes)
                if (fixes >= 2) {
                    runCatching { container.analyzer.analyze(curvedTrip) }
                } else {
                    container.repository.finishTrip(curvedTrip, System.currentTimeMillis())
                }
                Recorder.reset()
            }
        }
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.geno1024.ai.mapoftime.track.START"
        const val ACTION_STOP = "com.geno1024.ai.mapoftime.track.STOP"
        const val ACTION_MARK = "com.geno1024.ai.mapoftime.track.MARK"
        const val EXTRA_MODE = "mode"

        private const val MIN_TIME_MS = 1_000L
        private const val MAX_ACCURACY_METERS = 120f
        private const val LIVE_ACCURACY_METERS = 50.0
        private const val PROGRESS_EVERY = 10
        private const val NOTIFICATION_REFRESH_MS = 5_000L
        private const val RECENT_FIXES_LIMIT = 600
    }
}
