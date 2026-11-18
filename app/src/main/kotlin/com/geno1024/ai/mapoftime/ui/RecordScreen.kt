package com.geno1024.ai.mapoftime.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.track.RecordingPhase
import com.geno1024.ai.mapoftime.track.TrackPermissions
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.TrackMap
import kotlinx.coroutines.delay

@Composable
fun RecordScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val state by viewModel.recording.collectAsStateWithLifecycle()
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    var selectedMode by remember { mutableStateOf(TripMode.WALK) }
    var pendingMode by remember { mutableStateOf<TripMode?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val mode = pendingMode
        pendingMode = null
        if (mode != null) {
            if (TrackPermissions.hasLocation(context)) {
                viewModel.start(context, mode)
            } else {
                permissionDenied = true
            }
        }
    }

    fun requestStart(mode: TripMode) {
        permissionDenied = false
        val missing = TrackPermissions.missing(context)
        if (missing.isEmpty()) {
            viewModel.start(context, mode)
        } else {
            pendingMode = mode
            permissionLauncher.launch(missing)
        }
    }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastElapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(state.isRecording) {
        while (state.isRecording) {
            now = System.currentTimeMillis()
            lastElapsed = (now - state.startedAt).coerceAtLeast(0)
            delay(1_000)
        }
    }
    // Keep showing the length of the trip while it is being analyzed instead of
    // snapping back to zero the moment recording stops.
    val elapsed = when (state.phase) {
        RecordingPhase.IDLE -> 0L
        RecordingPhase.RECORDING -> (now - state.startedAt).coerceAtLeast(0)
        RecordingPhase.PROCESSING -> lastElapsed
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.record_title),
            subtitle = if (state.isRecording) state.mode.label(context) else null,
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = stringResource(R.string.record_mode)) {
                ModeFilter(
                    selected = if (state.isRecording) state.mode else selectedMode,
                    onSelect = { mode ->
                        if (mode != null) selectedMode = mode
                    },
                    includeAll = false,
                )
            }

            SectionCard {
                Text(
                    text = formatClockDuration(elapsed),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                StatRow(
                    stringResource(R.string.record_distance) to formatDistance(state.distanceM),
                    stringResource(R.string.record_fixes) to state.pointCount.toString(),
                )
                Spacer(Modifier.height(12.dp))
                StatRow(
                    stringResource(R.string.record_accuracy) to
                        (state.accuracyM?.let { "±${it.toInt()} m" } ?: "—"),
                    stringResource(R.string.record_marks) to state.marks.toString(),
                )

                Spacer(Modifier.height(16.dp))

                when (state.phase) {
                    RecordingPhase.PROCESSING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.record_processing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    RecordingPhase.RECORDING -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.mark(context) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.record_split))
                        }
                        Button(
                            onClick = { viewModel.stop(context) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(stringResource(R.string.record_stop))
                        }
                    }

                    RecordingPhase.IDLE -> {
                        Button(
                            onClick = { requestStart(selectedMode) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.record_start) + " · " + modeLabel(selectedMode))
                        }
                    }
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (permissionDenied) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.error_need_location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        state.isRecording -> stringResource(R.string.record_live_hint)
                        permissionDenied -> stringResource(R.string.record_permission)
                        else -> stringResource(R.string.record_idle_hint)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TrackMap(
                tracks = listOf(state.recentFixes),
                modifier = Modifier.fillMaxWidth().height(300.dp),
                markers = state.lastFix?.let { listOf(MapMarker(it, emphasized = true)) }.orEmpty(),
                tilesEnabled = settings.mapTiles,
                tileUrlTemplate = settings.tileUrlTemplate,
                focus = state.lastFix,
                emptyHint = stringResource(R.string.record_map_empty),
            )
        }
    }
}

/** `0:00`, `12:34`, `1:02:03` — a stopwatch, not a description. */
private fun formatClockDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
