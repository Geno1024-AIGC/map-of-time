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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.OsmOutcome
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.data.Leg
import com.geno1024.ai.mapoftime.data.MatchKind
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.RoadNames
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.data.traversalEndNames
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.TrackMap
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun TripDetailScreen(
    viewModel: AppViewModel,
    tripId: Long,
    onBack: () -> Unit,
    onEditLeg: (Long, Long) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
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
    var markTimes by remember(tripId) { mutableStateOf<List<Long>>(emptyList()) }
    var analyzing by remember { mutableStateOf(false) }
    var changingMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var noteDraft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var selectedLegId by remember(tripId) { mutableStateOf<Long?>(null) }
    var selectedEdge by remember(tripId) { mutableStateOf<Edge?>(null) }
    var trimming by remember(tripId) { mutableStateOf(false) }
    var cutIndex by remember(tripId) { mutableStateOf<Int?>(null) }
    var trimmingBusy by remember { mutableStateOf(false) }
    var splitting by remember(tripId) { mutableStateOf(false) }
    var splitIndex by remember(tripId) { mutableStateOf<Int?>(null) }
    var splittingBusy by remember { mutableStateOf(false) }
    var markBusy by remember { mutableStateOf(false) }

    LaunchedEffect(tripId, reload) {
        val tripValue = viewModel.trip(tripId)
        val legsValue = viewModel.legs(tripId)
        trip = tripValue
        legs = legsValue
        points = viewModel.points(tripId)
        markPositions = viewModel.markPositions(tripId)
        markTimes = viewModel.markTimes(tripId)
        identities = viewModel.identities(legsValue.mapNotNull { it.edgeId })
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
    // Naming the ends needs the edge, not just the leg: the names belong to
    // the two points, and the leg only says which way this trip went.
    LaunchedEffect(selectedLeg, reload) {
        selectedEdge = selectedLeg?.edgeId?.let { viewModel.edge(it) }
    }
    val selectedIdentity = remember(selectedLeg, identities) {
        selectedLeg?.let { leg ->
            when {
                leg.partial -> MatchKind.PARTIAL
                leg.edgeId == null -> MatchKind.UNMATCHED
                else -> identities[leg.edgeId] ?: MatchKind.UNMATCHED
            }
        }
    }
    val fromLabel = stringResource(R.string.edge_from)
    val toLabel = stringResource(R.string.edge_to)
    // The two ends of the selected leg, labelled with the edge's own
    // orientation so "from" and "to" mean the same thing here and in the
    // naming dialog. A partial leg stops at a junction inside the edge, so
    // its own ends are not the edge's ends and get no label.
    val endMarkers = remember(selectedLeg, selectedEdge, selectedTrack, fromLabel, toLabel) {
        val leg = selectedLeg
        val edge = selectedEdge
        val track = selectedTrack
        if (leg == null || edge == null || track == null || leg.partial) {
            emptyList()
        } else {
            val (startLabel, endLabel) = traversalEndNames(edge, leg.direction, fromLabel, toLabel)
            listOf(
                MapMarker(track.first(), emphasized = false, label = startLabel),
                MapMarker(track.last(), emphasized = true, label = endLabel),
            )
        }
    }
    val markers = remember(tracks, markPositions, endMarkers) {
        val endpoints = tracks.flatMap { track ->
            listOf(
                MapMarker(track.first(), emphasized = false),
                MapMarker(track.last(), emphasized = true),
            )
        }
        // Manually split points are worth seeing after the fact: they are the
        // only boundaries that do not come from the algorithm.
        endpoints + markPositions.map { MapMarker(it, emphasized = false) } + endMarkers
    }
    // While trimming, every raw fix is tappable so the tap index maps straight
    // back to `points`. Fixes after the cut fade out: they are about to go.
    val trimMarkers = remember(points, cutIndex) {
        val cut = cutIndex
        points.mapIndexed { index, point ->
            MapMarker(
                at = point.at,
                emphasized = index == cut,
                alpha = if (cut != null && index > cut) 0.25f else 1f,
            )
        }
    }
    // Post-hoc splitting uses the same raw dots as trimming, without fading
    // anything: the user is picking a cut, not deciding what to throw away.
    val splitMarkers = remember(points, splitIndex) {
        points.mapIndexed { index, point -> MapMarker(point.at, emphasized = index == splitIndex) }
    }
    // Everything up to and including the cut stays highlighted; the discarded
    // tail is still drawn as context so the choice can be judged in place.
    val cutPath = remember(points, cutIndex) {
        cutIndex?.let { index ->
            points.take(index + 1).map { it.at }.takeIf { it.size >= 2 }
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
                markers = when {
                    trimming -> trimMarkers
                    splitting -> splitMarkers
                    else -> markers
                },
                tilesEnabled = settings.mapTiles,
                basemap = settings.basemap,
                customTileUrl = settings.customTileUrl,
                customTilesGcj02 = settings.customTilesGcj02,
                highlight = cutPath ?: selectedTrack,
                onMarkerTap = when {
                    trimming -> { index -> cutIndex = index }
                    splitting -> { index -> splitIndex = index }
                    else -> null
                },
            )
            if (!trimming && !splitting) {
                selectedLeg?.let { leg ->
                    LegOverlay(
                        leg = leg,
                        identity = selectedIdentity,
                        onClear = { selectedLegId = null },
                        onEdit = if (selectedEdge != null) {
                            { onEditLeg(tripId, leg.id) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The picker sits right under the map: drag the arrow or tap a dot,
            // either moves the same selected fix. The mark uses its timestamp,
            // so the segmenter sees it exactly like one pressed while recording.
            if (splitting && points.size >= 2) {
                item {
                    SectionCard(
                        title = stringResource(R.string.trip_split),
                        subtitle = stringResource(R.string.trip_split_hint),
                    ) {
                        PointScrubber(points, splitIndex, onIndexChange = { splitIndex = it })
                        val selected = splitIndex
                        if (selected == null) {
                            Text(
                                text = stringResource(R.string.trip_split_pick),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.trip_split_summary,
                                    formatDateTime(points[selected].t),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { splitting = false; splitIndex = null },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Button(
                                onClick = {
                                    splitIndex?.let { index ->
                                        splittingBusy = true
                                        scope.launch {
                                            val result = viewModel.splitTripAt(tripId, points[index].t)
                                            splittingBusy = false
                                            splitting = false
                                            splitIndex = null
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
                                    }
                                },
                                enabled = splitIndex != null && !splittingBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.trip_split_confirm))
                            }
                        }
                    }
                }
            }

            // The scrubber sits right under the map so both views of the same
            // cut stay on screen: drag the arrow or tap a dot, either updates
            // the other. Points after the cut fade on the map.
            if (trimming && points.size >= 2) {
                item {
                    SectionCard(
                        title = stringResource(R.string.trip_trim),
                        subtitle = stringResource(R.string.trip_trim_hint),
                    ) {
                        val cut = cutIndex ?: points.lastIndex
                        PointScrubber(points, cutIndex, onIndexChange = { cutIndex = it })
                        if (cutIndex == null) {
                            Text(
                                text = stringResource(R.string.trip_trim_pick),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = stringResource(
                                    R.string.trip_trim_summary,
                                    cut + 1,
                                    points.size - cut - 1,
                                    formatDateTime(points[cut].t),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { trimming = false; cutIndex = null },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Button(
                                onClick = {
                                    cutIndex?.let { index ->
                                        trimmingBusy = true
                                        scope.launch {
                                            val result = viewModel.trimTripTail(tripId, index + 1)
                                            trimmingBusy = false
                                            trimming = false
                                            cutIndex = null
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
                                    }
                                },
                                enabled = cutIndex != null && cut >= 1 &&
                                    cut < points.lastIndex && !trimmingBusy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.trip_trim_confirm))
                            }
                        }
                    }
                }
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
                        if (markTimes.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.trip_marks),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.trip_marks_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            markTimes.forEach { t ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = formatDateTime(t),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            if (markBusy) return@TextButton
                                            markBusy = true
                                            scope.launch {
                                                val result = viewModel.removeSplitAt(tripId, t)
                                                markBusy = false
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
                                        enabled = !markBusy,
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        Text(stringResource(R.string.trip_mark_remove))
                                    }
                                }
                            }
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
                            leg.edgeId == null -> MatchKind.UNMATCHED
                            else -> identities[leg.edgeId] ?: MatchKind.UNMATCHED
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

            // Trimming only makes sense for a recording with raw fixes; an
            // imported trip has legs but nothing to cut, and cutting a live
            // recording would race the service still writing points.
            if (points.size >= 2 &&
                trip?.status != com.geno1024.ai.mapoftime.data.TripStatus.RECORDING
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                trimming = true
                                splitting = false
                                cutIndex = null
                                selectedLegId = null
                                // The picker is the first item; make sure it is
                                // in view even if the leg list was scrolled down.
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.trip_trim))
                        }
                        OutlinedButton(
                            onClick = {
                                splitting = true
                                trimming = false
                                splitIndex = null
                                selectedLegId = null
                                scope.launch { listState.animateScrollToItem(0) }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.trip_split))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
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

/**
 * Clock row plus the draggable arrow, shared by trimming and post-hoc splits.
 * The value is a fix index rather than an epoch millisecond: timestamps are
 * ~1.7e12 and lose precision as a Float, while dense tail fixes are much
 * easier to pick apart by index.
 */
@Composable
private fun PointScrubber(
    points: List<TrackPoint>,
    index: Int?,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = index ?: points.lastIndex
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatClock(points.first().t),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatClock(points[shown].t),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = formatClock(points.last().t),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val previousLabel = stringResource(R.string.trip_scrub_previous)
    val nextLabel = stringResource(R.string.trip_scrub_next)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onIndexChange(stepScrubIndex(points, shown, -1)) },
            enabled = shown > 0,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.semantics { contentDescription = previousLabel },
        ) {
            Text("←")
        }
        Slider(
            value = shown.toFloat(),
            valueRange = 0f..points.lastIndex.toFloat(),
            onValueChange = { value -> onIndexChange(value.toInt().coerceIn(0, points.lastIndex)) },
            modifier = modifier.weight(1f),
        )
        TextButton(
            onClick = { onIndexChange(stepScrubIndex(points, shown, +1)) },
            enabled = shown < points.lastIndex,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.semantics { contentDescription = nextLabel },
        ) {
            Text("→")
        }
    }
}

/** Below this average speed the fixes are a standstill, not travel. */
private const val STATIONARY_SPEED_MPS = 0.35

/** Fixes on either side used to decide whether a point is standing still. */
private const val STATIONARY_WINDOW = 2

/** One tap never skips more than this many fixes, even in a very long stop. */
private const val MAX_SCRUB_STEP = 100

/**
 * A phone lying still still jitters a metre or two between fixes. Comparing
 * fixes a couple of steps apart cancels that noise: over a window a standstill
 * goes nowhere, while even slow travel keeps moving away. The window looks
 * back the way we came ([direction]) so the first moving fix after a stop is
 * not mistaken for part of it.
 */
private fun isStationary(points: List<TrackPoint>, index: Int, direction: Int): Boolean {
    val last = points.lastIndex
    var first: Int
    var end: Int
    if (direction > 0) {
        first = (index - STATIONARY_WINDOW).coerceAtLeast(0)
        end = index
        if (end == first && first < last) end = (index + STATIONARY_WINDOW).coerceAtMost(last)
    } else {
        first = index
        end = (index + STATIONARY_WINDOW).coerceAtMost(last)
        if (end == first && first > 0) first = (index - STATIONARY_WINDOW).coerceAtLeast(0)
    }
    if (end <= first) return false
    val seconds = (points[end].t - points[first].t) / 1000.0
    if (seconds <= 0.0) return false
    val distance = Geo.distanceMeters(points[first].at, points[end].at)
    return distance / seconds <= STATIONARY_SPEED_MPS && distance <= 6.0
}

/**
 * Where an arrow tap lands. Moving through ordinary fixes is one step at a
 * time; once the fixes around [from] stop moving, one tap crosses the whole
 * standstill instead of stepping through a pile of GPS noise.
 */
internal fun stepScrubIndex(points: List<TrackPoint>, from: Int, direction: Int): Int {
    val last = points.lastIndex
    if (last <= 0) return 0
    var step = 1
    if (isStationary(points, from, direction)) {
        while (step < MAX_SCRUB_STEP) {
            val next = from + direction * step
            if (next !in 0..last || !isStationary(points, next, direction)) break
            step++
        }
    }
    return (from + direction * step).coerceIn(0, last)
}

@Composable
private fun LegOverlay(
    leg: Leg,
    identity: MatchKind?,
    onClear: () -> Unit,
    onEdit: (() -> Unit)?,
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
                        if (leg.direction > 0) R.string.edge_forward else R.string.edge_backward,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onEdit != null) {
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("✎")
                }
            }
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
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
                    text = leg.label ?: stringResource(R.string.leg_unnamed),
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

