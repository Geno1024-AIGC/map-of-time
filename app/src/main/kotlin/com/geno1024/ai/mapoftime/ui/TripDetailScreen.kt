package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
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
    val noPointsText = stringResource(R.string.trip_no_points)
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
    var changingMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var selectedLegId by remember(tripId) { mutableStateOf<Long?>(null) }

    LaunchedEffect(tripId, reload) {
        val tripValue = viewModel.trip(tripId)
        val legsValue = viewModel.legs(tripId)
        trip = tripValue
        legs = legsValue
        points = viewModel.points(tripId)
        markPositions = viewModel.markPositions(tripId)
        identities = viewModel.identities(legsValue.mapNotNull { it.segmentId })
        // Re-analysis replaces every leg, so an old selection would point at
        // an id that no longer exists.
        if (legsValue.none { it.id == selectedLegId }) selectedLegId = null
    }

    val tracks = remember(points, legs) {
        if (points.size >= 2) {
            listOf(points.map { it.at })
        } else {
            legs.map { Polyline.decode(it.polyline) }.filter { it.size >= 2 }
        }
    }
    val selectedLeg = remember(legs, selectedLegId) { legs.firstOrNull { it.id == selectedLegId } }
    val selectedTrack = remember(selectedLeg) {
        selectedLeg?.let { Polyline.decode(it.polyline) }?.takeIf { it.size >= 2 }
    }
    val selectedIdentity = remember(selectedLeg, identities) {
        selectedLeg?.let { leg ->
            when {
                leg.partial -> MatchKind.PARTIAL
                leg.segmentId == null -> MatchKind.UNMATCHED
                else -> identities[leg.segmentId] ?: MatchKind.UNMATCHED
            }
        }
    }
    val markers = remember(tracks, markPositions, selectedTrack) {
        val endpoints = tracks.flatMap { track ->
            listOf(
                MapMarker(track.first(), emphasized = false),
                MapMarker(track.last(), emphasized = true),
            )
        }
        // Manually split points are worth seeing after the fact: they are the
        // only boundaries that do not come from the algorithm.
        endpoints +
            markPositions.map { MapMarker(it, emphasized = false) } +
            selectedTrack.orEmpty().let { track ->
                if (track.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        MapMarker(track.first(), emphasized = false),
                        MapMarker(track.last(), emphasized = true),
                    )
                }
            }
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

    if (editingNote) {
        AlertDialog(
            onDismissRequest = { editingNote = false },
            title = { Text(stringResource(R.string.trip_note)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.trip_note_hint)) },
                    minLines = 3,
                    maxLines = 6,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingNote = false
                        scope.launch {
                            viewModel.setTripNote(tripId, noteDraft)
                            reload += 1
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = false }) {
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

        // Pinned: the map and the selected leg stay in place while the list
        // below scrolls. Picking a leg then never means scrolling back up to
        // look at the map, which is exactly what comparing legs is about.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(240.dp),
        ) {
            TrackMap(
                tracks = tracks,
                modifier = Modifier.fillMaxSize(),
                markers = markers,
                tilesEnabled = settings.mapTiles,
                basemap = settings.basemap,
                customTileUrl = settings.customTileUrl,
                customTilesGcj02 = settings.customTilesGcj02,
                highlight = selectedTrack,
            )
            selectedLeg?.let { leg ->
                LegOverlay(
                    leg = leg,
                    identity = selectedIdentity,
                    onClear = { selectedLegId = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = value.note ?: stringResource(R.string.trip_note_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (value.note == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    noteDraft = value.note.orEmpty()
                                    editingNote = true
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (value.note == null) R.string.trip_note_add else R.string.action_edit,
                                    ),
                                )
                            }
                        } 
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.trip_mode),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        ModeFilter(
                            selected = value.mode,
                            includeAll = false,
                            onSelect = { picked ->
                                if (picked == null || picked == value.mode || changingMode) {
                                    return@ModeFilter
                                }
                                scope.launch {
                                    changingMode = true
                                    val result = viewModel.setTripMode(tripId, picked)
                                    changingMode = false
                                    message = result?.let {
                                        String.format(
                                            Locale.getDefault(),
                                            analysisDoneFormat,
                                            it.legCount,
                                            it.osmLegs,
                                            osmStatus.getValue(it.osmOutcome),
                                        )
                                    } ?: noPointsText
                                    reload += 1
                                }
                            },
                        )
                        if (changingMode) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.trip_analyzing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.trip_legs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
                if (legs.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.trip_legs_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
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
                        selected = leg.id == selectedLegId,
                        onClick = {
                            selectedLegId = if (selectedLegId == leg.id) null else leg.id
                        },
                    )
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

            // Re-analysing and deleting are rare, destructive or slow actions;
            // they belong after the data, not between it and the map.
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
                                } ?: noPointsText
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
        }
    }
}

@Composable
private fun LegOverlay(
    leg: Leg,
    identity: MatchKind?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = leg.label ?: stringResource(R.string.leg_unnamed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    identity?.let {
                        Spacer(Modifier.width(8.dp))
                        MatchBadge(it)
                    }
                }
                Text(
                    text = buildString {
                        append(stringResource(R.string.leg_time_short, formatDuration(leg.durationMs)))
                        append(" · ")
                        append(formatDistance(leg.distanceM))
                        if (leg.stoppedMs > 0) {
                            append(" · ")
                            append(stringResource(R.string.leg_wait_short, formatSeconds(leg.stoppedMs)))
                        }
                        if (leg.signalWaitMs > 0) {
                            append(" · ")
                            append(stringResource(R.string.leg_signal_short, formatSeconds(leg.signalWaitMs)))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.leg_window,
                        formatClock(leg.startMs),
                        formatClock(leg.endMs),
                    ) + " · " + stringResource(
                        if (leg.direction > 0) R.string.segment_forward else R.string.segment_backward,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear) {
                Text("✕")
            }
        }
    }
}

@Composable
private fun LegRow(
    index: Int,
    leg: Leg,
    identity: MatchKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val decoration = if (selected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.secondary, shape)
    } else {
        Modifier
    }
    SectionCard(modifier = decoration.clickable(onClick = onClick)) {
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
                        if (leg.signalWaitMs > 0) {
                            append(" · ")
                            append(stringResource(R.string.sample_signal_wait, formatSeconds(leg.signalWaitMs)))
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
