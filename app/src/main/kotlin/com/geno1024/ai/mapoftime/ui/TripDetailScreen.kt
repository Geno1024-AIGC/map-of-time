package com.geno1024.ai.mapoftime.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.OsmOutcome
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.data.Leg
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.TrackMap
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun TripDetailScreen(
    viewModel: AppViewModel,
    tripId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    val analysisDoneFormat = stringResource(R.string.trip_analysis_done)
    val osmStatus = mapOf(
        OsmOutcome.MATCHED to stringResource(R.string.osm_matched),
        OsmOutcome.DISABLED to stringResource(R.string.osm_disabled),
        OsmOutcome.TOO_LARGE to stringResource(R.string.osm_too_large),
        OsmOutcome.UNAVAILABLE to stringResource(R.string.osm_unavailable),
        OsmOutcome.NO_DATA to stringResource(R.string.osm_no_data),
    )

    var trip by remember(tripId) { mutableStateOf<Trip?>(null) }
    var legs by remember(tripId) { mutableStateOf<List<Leg>>(emptyList()) }
    var points by remember(tripId) { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var identities by remember(tripId) { mutableStateOf<Map<Long, MatchKind>>(emptyMap()) }
    var markPositions by remember(tripId) { mutableStateOf<List<com.geno1024.ai.mapoftime.core.LatLon>>(emptyList()) }
    var analyzing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(tripId, reload) {
        val tripValue = viewModel.trip(tripId)
        val legsValue = viewModel.legs(tripId)
        trip = tripValue
        legs = legsValue
        points = viewModel.points(tripId)
        markPositions = viewModel.markPositions(tripId)
        identities = viewModel.identities(legsValue.mapNotNull { it.segmentId })
    }

    val tracks = remember(points, legs) {
        if (points.size >= 2) {
            listOf(points.map { it.at })
        } else {
            legs.map { Polyline.decode(it.polyline) }.filter { it.size >= 2 }
        }
    }
    val markers = remember(tracks, markPositions) {
        val endpoints = tracks.flatMap { track ->
            listOf(
                MapMarker(track.first(), emphasized = false),
                MapMarker(track.last(), emphasized = true),
            )
        }
        // Manually split points are worth seeing after the fact: they are the
        // only boundaries that do not come from the algorithm.
        endpoints + markPositions.map { MapMarker(it, emphasized = false) }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.trip_delete)) },
            text = { Text(stringResource(R.string.trip_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            viewModel.deleteTrip(tripId)
                            onBack()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.trip_detail_title),
            subtitle = trip?.let { formatDateTime(it.startedAt) },
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TrackMap(
                    tracks = tracks,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    markers = markers,
                    tilesEnabled = settings.mapTiles,
                    basemap = settings.basemap,
                    customTileUrl = settings.customTileUrl,
                    customTilesGcj02 = settings.customTilesGcj02,
                )
            }

            item {
                SectionCard {
                    val value = trip
                    if (value == null) {
                        Text(stringResource(R.string.samples_none))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = modeLabel(value.mode),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(
                                        when (value.status) {
                                            com.geno1024.ai.mapoftime.data.TripStatus.RECORDING ->
                                                R.string.trip_status_recording
                                            com.geno1024.ai.mapoftime.data.TripStatus.INTERRUPTED ->
                                                R.string.trip_status_interrupted
                                            com.geno1024.ai.mapoftime.data.TripStatus.FINISHED ->
                                                R.string.trip_status_finished
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Pill(
                                text = pluralStringResource(R.plurals.trip_legs_count, value.legCount, value.legCount),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        StatRow(
                            stringResource(R.string.record_distance) to formatDistance(value.distanceM),
                            stringResource(R.string.trip_duration) to formatDuration(value.durationMs),
                            stringResource(R.string.trip_points) to value.pointCount.toString(),
                        )
                        if (markPositions.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            KeyValueRow(
                                stringResource(R.string.trip_marks),
                                markPositions.size.toString(),
                            )
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            if (analyzing) return@Button
                            scope.launch {
                                analyzing = true
                                val result = viewModel.analyze(tripId)
                                analyzing = false
                                message = result?.let {
                                    String.format(
                                        Locale.getDefault(),
                                        analysisDoneFormat,
                                        it.legCount,
                                        it.osmLegs,
                                        osmStatus.getValue(it.osmOutcome),
                                    )
                                }
                                reload += 1
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !analyzing,
                    ) {
                        if (analyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.trip_analyzing))
                        } else {
                            Text(stringResource(R.string.trip_reanalyze))
                        }
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.trip_delete))
                    }
                }
            }

            message?.let { text ->
                item {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.trip_legs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            if (legs.isEmpty()) {
                item {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.trip_no_legs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(legs, key = { _, leg -> leg.id }) { index, leg ->
                    LegRow(
                        index = index,
                        leg = leg,
                        identity = when {
                            leg.partial -> MatchKind.PARTIAL
                            leg.segmentId == null -> MatchKind.UNMATCHED
                            else -> identities[leg.segmentId] ?: MatchKind.UNMATCHED
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LegRow(index: Int, leg: Leg, identity: MatchKind) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%02d".format(index + 1),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = leg.label ?: formatClock(leg.startMs),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    text = buildString {
                        append(formatDistance(leg.distanceM))
                        append(" · ")
                        append(formatDuration(leg.durationMs))
                        if (leg.stoppedMs > 0) {
                            append(" · ")
                            append(stringResource(R.string.sample_waiting, formatSeconds(leg.stoppedMs)))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MatchBadge(identity)
        }
    }
}
