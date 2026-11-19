package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.data.TripStatus
import kotlinx.coroutines.launch

@Composable
fun TripsScreen(
    viewModel: AppViewModel,
    onOpenTrip: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The list may have grown while a trip was being recorded in the
    // background, so re-read it whenever the tab is shown.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }

    fun reanalyze(ids: List<Long>) {
        if (ids.isEmpty() || running) return
        scope.launch {
            running = true
            progress = 0 to ids.size
            viewModel.analyzeAll(ids) { done, total -> progress = done to total }
            running = false
            selecting = false
            selected = emptySet()
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.trips_title),
            subtitle = if (viewModel.trips.isEmpty()) null else viewModel.trips.size.toString(),
            actions = {
                if (viewModel.trips.isNotEmpty() && !running) {
                    TextButton(
                        onClick = {
                            if (selecting) {
                                selecting = false
                                selected = emptySet()
                            } else {
                                selecting = true
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (selecting) R.string.action_cancel else R.string.trips_select,
                            ),
                        )
                    }
                    if (!selecting) {
                        TextButton(onClick = { reanalyze(viewModel.trips.map { it.id }) }) {
                            Text(stringResource(R.string.trips_reanalyze_all))
                        }
                    }
                }
            },
        )

        if (running) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(
                        R.string.trips_reanalyzing,
                        progress.first,
                        progress.second,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (selecting && !running) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        selected = if (selected.size == viewModel.trips.size) {
                            emptySet()
                        } else {
                            viewModel.trips.map { it.id }.toSet()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (selected.size == viewModel.trips.size) {
                                R.string.trips_select_none
                            } else {
                                R.string.trips_select_all
                            },
                        ),
                    )
                }
                Button(
                    onClick = { reanalyze(viewModel.trips.map { it.id }.filter { it in selected }) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.trips_reanalyze_selected, selected.size))
                }
            }
        }

        if (viewModel.trips.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.trips_empty_title),
                body = stringResource(R.string.trips_empty_body),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(viewModel.trips, key = { it.id }) { trip ->
                TripCard(
                    trip = trip,
                    selecting = selecting,
                    selected = trip.id in selected,
                    onClick = {
                        if (selecting) {
                            selected = if (trip.id in selected) {
                                selected - trip.id
                            } else {
                                selected + trip.id
                            }
                        } else {
                            onOpenTrip(trip.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TripCard(
    trip: Trip,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
                Spacer(Modifier.padding(end = 4.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatDateTime(trip.startedAt),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = modeLabel(trip.mode) + " · " + pluralStringResource(R.plurals.trip_legs_count, trip.legCount, trip.legCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (trip.status != TripStatus.FINISHED) {
                Pill(
                    text = stringResource(
                        when (trip.status) {
                            TripStatus.RECORDING -> R.string.trip_status_recording
                            TripStatus.INTERRUPTED -> R.string.trip_status_interrupted
                            TripStatus.FINISHED -> R.string.trip_status_finished
                        },
                    ),
                    color = if (trip.status == TripStatus.RECORDING) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        StatRow(
            stringResource(R.string.record_distance) to formatDistance(trip.distanceM),
            stringResource(R.string.trip_duration) to formatDuration(trip.durationMs),
            stringResource(R.string.trip_points) to trip.pointCount.toString(),
        )
    }
}
