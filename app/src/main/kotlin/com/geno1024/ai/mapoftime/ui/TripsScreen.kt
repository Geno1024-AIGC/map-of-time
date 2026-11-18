package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.data.TripStatus

@Composable
fun TripsScreen(
    viewModel: AppViewModel,
    onOpenTrip: (Long) -> Unit,
) {
    // The list may have grown while a trip was being recorded in the
    // background, so re-read it whenever the tab is shown.
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.trips_title),
            subtitle = if (viewModel.trips.isEmpty()) null else viewModel.trips.size.toString(),
        )

        if (viewModel.trips.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.trips_empty_title),
                body = stringResource(R.string.trips_empty_body),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(viewModel.trips, key = { it.id }) { trip ->
                TripCard(trip = trip, onClick = { onOpenTrip(trip.id) })
            }
        }
    }
}

@Composable
private fun TripCard(trip: Trip, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
