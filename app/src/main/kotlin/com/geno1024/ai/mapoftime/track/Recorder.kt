package com.geno1024.ai.mapoftime.track

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordingPhase { IDLE, RECORDING, PROCESSING }

data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val tripId: Long? = null,
    val mode: TripMode = TripMode.WALK,
    val startedAt: Long = 0L,
    val pointCount: Int = 0,
    val distanceM: Double = 0.0,
    val accuracyM: Double? = null,
    val lastFixAt: Long? = null,
    val lastFix: LatLon? = null,
    val marks: Int = 0,
    val error: String? = null,
    /** The tail of the trace, so the record screen can draw it live. */
    val recentFixes: List<LatLon> = emptyList(),
) {
    val isRecording: Boolean get() = phase == RecordingPhase.RECORDING
}

/**
 * The recorder's state lives here rather than inside the service so the UI can
 * observe it across configuration changes and after the service has stopped.
 */
object Recorder {
    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal fun publish(state: RecordingState) {
        _state.value = state
    }

    fun start(context: Context, mode: TripMode) {
        if (_state.value.phase == RecordingPhase.RECORDING) return
        _state.value = RecordingState(phase = RecordingPhase.RECORDING, mode = mode, startedAt = System.currentTimeMillis())
        val intent = Intent(context, TrackService::class.java).setAction(TrackService.ACTION_START)
        intent.putExtra(TrackService.EXTRA_MODE, mode.id)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, TrackService::class.java).setAction(TrackService.ACTION_STOP)
        context.startService(intent)
    }

    /** Cuts the current leg at "now": a corner the turn detector missed. */
    fun mark(context: Context) {
        if (!_state.value.isRecording) return
        val intent = Intent(context, TrackService::class.java).setAction(TrackService.ACTION_MARK)
        context.startService(intent)
    }

    internal fun reset() {
        _state.value = RecordingState()
    }
}
