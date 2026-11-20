package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.PlaceDraft
import com.geno1024.ai.mapoftime.analysis.PlaceDrafts
import com.geno1024.ai.mapoftime.core.DayKind
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.PeakHour
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.core.TimeBucket
import com.geno1024.ai.mapoftime.core.TrafficContext
import com.geno1024.ai.mapoftime.core.groupByTrafficContext
import com.geno1024.ai.mapoftime.core.samples
import com.geno1024.ai.mapoftime.core.TraversalSample
import com.geno1024.ai.mapoftime.data.Leg
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.data.Segment
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.MapRing
import com.geno1024.ai.mapoftime.ui.map.TrackMap
import kotlinx.coroutines.launch

@Composable
fun SegmentDetailScreen(
    viewModel: AppViewModel,
    segmentId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var segment by remember(segmentId) { mutableStateOf<Segment?>(null) }
    var samples by remember(segmentId) { mutableStateOf<List<TraversalSample>>(emptyList()) }
    var legs by remember(segmentId) { mutableStateOf<List<Leg>>(emptyList()) }
    var renaming by remember { mutableStateOf(false) }
    // Same two steps as the network screen: preview the circle, then name it.
    var preview by remember { mutableStateOf<PlaceDraft?>(null) }
    var namingPlace by remember { mutableStateOf<PlaceDraft?>(null) }

    LaunchedEffect(segmentId) {
        segment = viewModel.segment(segmentId)
        samples = viewModel.samples(segmentId)
        legs = viewModel.legsForSegment(segmentId)
    }

    val stats = remember(samples) { DurationStats.of(samples) }
    val forward = remember(samples) { DurationStats.of(samples.filter { it.direction > 0 }) }
    val backward = remember(samples) { DurationStats.of(samples.filter { it.direction < 0 }) }
    val byBucket = remember(samples) {
        TimeBucket.ordered.associateWith { DurationStats.of(it.samples(samples)) }
    }
    val byContext = remember(samples) {
        val grouped = groupByTrafficContext(samples)
        TrafficContext.ordered.associateWith { DurationStats.of(grouped[it].orEmpty()) }
    }

    val tracks = remember(legs) {
        legs.map { Polyline.decode(it.polyline) }.filter { it.size >= 2 }
    }
    val fromLabel = stringResource(R.string.segment_from)
    val toLabel = stringResource(R.string.segment_to)
    // Both ends of a piece inside one junction carry the same name; saying
    // "X → X" would be noise, so the direction is labelled "inside X" instead.
    val insideJunction = segment?.let { it.fromName?.takeIf { name -> name == it.toName } }
    val forwardLabel = segment?.forwardName
        ?: insideJunction?.let { stringResource(R.string.segment_inside_junction, it) }
        ?: "$fromLabel → $toLabel"
    val backwardLabel = segment?.backwardName
        ?: insideJunction?.let { stringResource(R.string.segment_inside_junction, it) }
        ?: "$toLabel → $fromLabel"
    val markers = remember(segment, fromLabel, toLabel, preview) {
        val members = preview?.refs?.mapTo(HashSet()) { it.key } ?: emptySet()
        segment?.let {
            listOf(
                MapMarker(
                    at = it.from,
                    emphasized = it.fromPoint?.key?.let { key -> key in members } == true,
                    label = fromLabel,
                ),
                MapMarker(it.to, emphasized = true, label = toLabel),
            )
        }.orEmpty()
    }
    val rings = remember(viewModel.places, preview) {
        buildList {
            for (place in viewModel.places) {
                add(MapRing(place.at, place.radiusM.coerceAtLeast(PlaceDrafts.MIN_ADOPT_M)))
            }
            preview?.let { add(MapRing(it.center, it.adoptRadius(), active = true)) }
        }
    }
    val focusBounds = remember(preview) {
        preview?.let { draft ->
            Geo.bounds(listOf(draft.center))?.expanded(draft.adoptRadius() + 25.0)
        }
    }

    if (renaming) {
        SegmentNameDialog(
            initial = segment?.userName.orEmpty(),
            osmName = segment?.name,
            onDismiss = { renaming = false },
            onSave = { name ->
                renaming = false
                scope.launch {
                    viewModel.renameSegment(segmentId, name)
                    segment = viewModel.segment(segmentId)
                    legs = viewModel.legsForSegment(segmentId)
                }
            },
        )
    }

    // Naming a junction names every point the app grouped under it, which is
    // the only way a crossing with a dozen corners stays one action.
    namingPlace?.let { draft ->
        PlaceNameDialog(
            initial = viewModel.placeName(draft.existingPlaceId) ?: draft.seed.key.let { key ->
                when (key) {
                    segment?.fromKey -> segment?.fromName
                    segment?.toKey -> segment?.toName
                    else -> null
                }
            }.orEmpty(),
            pointCount = draft.count,
            spanM = draft.spanM,
            named = draft.existingPlaceId != null,
            overriddenCount = viewModel.overriddenMembers(draft),
            onDismiss = {
                namingPlace = null
                preview = null
            },
            onSave = { name, takeOverNames ->
                namingPlace = null
                preview = null
                scope.launch {
                    viewModel.namePlace(draft, name, clearMemberNames = takeOverNames)
                    segment = viewModel.segment(segmentId)
                    legs = viewModel.legsForSegment(segmentId)
                }
            },
            onSavePointOnly = { name ->
                namingPlace = null
                preview = null
                scope.launch {
                    viewModel.renamePoint(draft.seed, name)
                    segment = viewModel.segment(segmentId)
                    legs = viewModel.legsForSegment(segmentId)
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = segment?.displayName ?: segment?.key ?: stringResource(R.string.segments_title),
            subtitle = segment?.let {
                val distance = stats.medianDistanceM.takeIf { d -> d > 0 } ?: it.lengthM
                modeLabel(it.mode) + " · " + formatDistance(distance)
            },
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
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    markers = markers,
                    tilesEnabled = settings.mapTiles,
                    basemap = settings.basemap,
                    customTileUrl = settings.customTileUrl,
                    customTilesGcj02 = settings.customTilesGcj02,
                    rings = rings,
                    focusBounds = focusBounds,
                )
            }

            // The circle is on the map, the count is here, nothing is saved.
            preview?.let { draft ->
                item {
                    PlacePreviewRow(
                        draft = draft,
                        onName = { namingPlace = draft },
                        onCancel = { preview = null },
                    )
                }
            }

            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.segment_name),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = segment?.displayName ?: "—",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val osmName = segment?.name
                            if (segment?.userName != null && osmName != null) {
                                Text(
                                    text = stringResource(R.string.segment_osm_name, osmName),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val pair = segment?.pointName
                            if (segment?.userName != null && pair != null) {
                                Text(
                                    text = stringResource(R.string.segment_from_points, pair),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { renaming = true }) {
                            Text(
                                stringResource(
                                    if (segment?.userName == null) {
                                        R.string.segment_name_add
                                    } else {
                                        R.string.action_edit
                                    },
                                ),
                            )
                        }
                    }

                    val fromPoint = segment?.fromPoint
                    val toPoint = segment?.toPoint
                    if (fromPoint != null && toPoint != null) {
                        ThinDivider()
                        EndpointRow(
                            label = stringResource(R.string.segment_from),
                            name = segment?.fromName,
                            note = placeNote(viewModel, fromPoint),
                            onName = {
                                preview = viewModel.placeDraft(fromPoint, segment?.from)
                            },
                        )
                        EndpointRow(
                            label = stringResource(R.string.segment_to),
                            name = segment?.toName,
                            note = placeNote(viewModel, toPoint),
                            onName = {
                                preview = viewModel.placeDraft(toPoint, segment?.to)
                            },
                        )
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.segment_stats_title)) {
                    if (segment != null) {
                        MatchBadge(segment!!.identity)
                        Spacer(Modifier.height(10.dp))
                    }
                    // A divided road has two carriageways between the same
                    // crossings: one number would describe neither of them.
                    if (forward.count > 0 && backward.count > 0) {
                        DirectionHeadline(forwardLabel, forward)
                        Spacer(Modifier.height(10.dp))
                        DirectionHeadline(backwardLabel, backward)
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = if (stats.count == 0) "—" else formatSeconds(stats.typicalMs),
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = pluralStringResource(R.plurals.samples_count, stats.count, stats.count),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ConfidenceBadge(stats.confidence)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    if (stats.count > 0) {
                        KeyValueRow(
                            stringResource(R.string.segment_moving_time),
                            formatSeconds(stats.medianMovingMs),
                        )
                        KeyValueRow(
                            stringResource(R.string.segment_waiting_time),
                            formatSeconds(stats.medianStoppedMs),
                        )
                        if (stats.waitedSamples > 0) {
                            KeyValueRow(
                                stringResource(R.string.segment_waited_in),
                                stringResource(
                                    R.string.segment_waited_in_value,
                                    stats.waitedSamples,
                                    stats.count,
                                ),
                            )
                            KeyValueRow(
                                stringResource(R.string.segment_wait_when_stopped),
                                formatSeconds(stats.medianWaitedMs),
                            )
                        }
                        if (stats.signalWaitedSamples > 0) {
                            KeyValueRow(
                                stringResource(R.string.segment_signal_wait),
                                formatSeconds(stats.medianSignalWaitMs),
                            )
                            KeyValueRow(
                                stringResource(R.string.segment_signal_waited_in),
                                stringResource(
                                    R.string.segment_waited_in_value,
                                    stats.signalWaitedSamples,
                                    stats.count,
                                ),
                            )
                            KeyValueRow(
                                stringResource(R.string.segment_signal_when_stopped),
                                formatSeconds(stats.medianSignalWaitedMs),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    KeyValueRow(
                        stringResource(R.string.segment_p25_p75),
                        if (stats.count == 0) "—" else
                            formatSeconds(stats.p25Ms) + " – " + formatSeconds(stats.p75Ms),
                    )
                    KeyValueRow(
                        stringResource(R.string.segment_range),
                        if (stats.count == 0) "—" else
                            formatSeconds(stats.minMs) + " – " + formatSeconds(stats.maxMs),
                    )
                    KeyValueRow(
                        stringResource(R.string.segment_trimmed_mean),
                        if (stats.count == 0) "—" else formatSeconds(stats.trimmedMeanMs),
                    )
                    KeyValueRow(
                        stringResource(R.string.segment_mad),
                        if (stats.count == 0) "—" else formatSeconds(stats.medianAbsoluteDeviationMs),
                    )
                    KeyValueRow(
                        stringResource(R.string.segment_outliers),
                        "${stats.outlierCount} / ${stats.count}",
                    )
                    KeyValueRow(
                        stringResource(R.string.segment_pace),
                        formatPace(stats.typicalPaceSecondsPerKm),
                    )
                    KeyValueRow(
                        stringResource(R.string.segment_distance),
                        formatDistance(stats.medianDistanceM),
                    )
                    segment?.let {
                        KeyValueRow(
                            stringResource(R.string.segment_first_seen),
                            formatDateTime(it.firstSeen),
                        )
                        KeyValueRow(
                            stringResource(R.string.segment_last_seen),
                            formatDateTime(it.lastSeen),
                        )
                    }
                }
            }

            if (samples.size >= 2) {
                item {
                    SectionCard(title = stringResource(R.string.segment_directions)) {
                        DirectionRow(
                            label = forwardLabel,
                            stats = forward,
                            reference = stats.medianMs,
                        )
                        Spacer(Modifier.height(8.dp))
                        DirectionRow(
                            label = backwardLabel,
                            stats = backward,
                            reference = stats.medianMs,
                        )
                    }
                }

                item {
                    SectionCard(
                        title = stringResource(R.string.segment_by_day),
                        subtitle = stringResource(R.string.segment_by_day_hint),
                    ) {
                        TrafficMatrix(byContext)
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.segment_by_time)) {
                        TimeBucket.ordered.forEach { bucket ->
                            val bucketStats = byBucket.getValue(bucket)
                            KeyValueRow(
                                label = timeBucketLabel(bucket),
                                value = if (bucketStats.count == 0) {
                                    "—"
                                } else {
                                    formatSeconds(bucketStats.medianMs) + " · " +
                                        pluralStringResource(
                                            R.plurals.samples_count,
                                            bucketStats.count,
                                            bucketStats.count,
                                        )
                                },
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = stringResource(R.string.segment_samples_title)) {
                        DurationBars(samples = samples, stats = stats)
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.segment_samples_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }

            if (samples.isEmpty()) {
                item {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.samples_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(samples, key = { it.startedAt }) { sample ->
                    SampleRow(sample = sample, stats = stats)
                }
            }
        }
    }
}

/**
 * Weekday / weekend against peak / off-peak, as a two-by-two table.
 *
 * Reading down a column answers "how much slower is the rush"; reading across
 * a row answers "is Saturday really quieter". Four key-value rows would make
 * the reader hold one of the axes in their head; the grid does it for them.
 */
@Composable
private fun TrafficMatrix(rows: Map<TrafficContext, DurationStats>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(LABEL_WEIGHT))
        for (peak in PeakHour.entries) {
            Text(
                text = peakLabel(peak),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                textAlign = TextAlign.End,
            )
        }
    }
    for (day in DayKind.entries) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayKindLabel(day),
                modifier = Modifier.weight(LABEL_WEIGHT),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            for (peak in PeakHour.entries) {
                val stats = rows[TrafficContext(day, peak)]
                val count = stats?.count ?: 0
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = if (count == 0) "—" else formatSeconds(stats!!.medianMs),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (count > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.samples_count, count, count),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                        )
                    }
                }
            }
        }
    }
}

/** Wide enough for "Off-peak" in the header, the rest split evenly. */
private const val LABEL_WEIGHT = 1.15f

@Composable
private fun DirectionRow(label: String, stats: DurationStats, reference: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = sampleLine(stats),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (stats.count == 0) "—" else formatSeconds(stats.medianMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (stats.count > 0 && reference > 0) {
                Text(
                    text = formatPercentDelta(stats.medianMs, reference),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One bar per traversal, sorted by duration; outliers stand out in the error colour. */
@Composable
private fun DurationBars(samples: List<TraversalSample>, stats: DurationStats) {
    val colors = MaterialTheme.colorScheme
    val iqr = (stats.p75Ms - stats.p25Ms).coerceAtLeast(0)
    val lowFence = stats.p25Ms - (iqr * 1.5).toLong()
    val highFence = stats.p75Ms + (iqr * 1.5).toLong()
    val sorted = remember(samples) { samples.map { it.durationMs }.sorted() }
    val maxValue = (sorted.lastOrNull() ?: 1).coerceAtLeast(1)

    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        if (sorted.isEmpty()) return@Canvas
        val slot = size.width / sorted.size
        val barWidth = (slot * 0.62f).coerceAtLeast(2f)
        sorted.forEachIndexed { index, value ->
            val height = (value.toFloat() / maxValue) * size.height
            val outlier = value < lowFence || value > highFence
            drawRect(
                color = if (outlier) colors.error.copy(alpha = 0.8f) else colors.primary.copy(alpha = 0.85f),
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - height),
                size = Size(barWidth, height),
            )
        }
        val medianX = (
            sorted.indexOfFirst { it >= stats.medianMs }.coerceAtLeast(0) * slot
            )
        drawLine(
            color = colors.onSurface.copy(alpha = 0.6f),
            start = Offset(medianX, 0f),
            end = Offset(medianX, size.height),
            strokeWidth = 1.5f,
        )
    }
}

@Composable
private fun SampleRow(sample: TraversalSample, stats: DurationStats) {
    val iqr = (stats.p75Ms - stats.p25Ms).coerceAtLeast(0)
    val lowFence = stats.p25Ms - (iqr * 1.5).toLong()
    val highFence = stats.p75Ms + (iqr * 1.5).toLong()
    val outlier = sample.durationMs < lowFence || sample.durationMs > highFence

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatDateTime(sample.startedAt),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = buildString {
                        append(formatDistance(sample.distanceM))
                        append(" · ")
                        append(sample.directionLabel())
                        if (sample.stoppedMs > 0) {
                            append(" · ")
                            append(stringResource(R.string.sample_waiting, formatSeconds(sample.stoppedMs)))
                        }
                        if (sample.signalWaitMs > 0) {
                            append(" · ")
                            append(stringResource(R.string.sample_signal_wait, formatSeconds(sample.signalWaitMs)))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatSeconds(sample.durationMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (outlier) {
                    Pill(
                        text = stringResource(R.string.segment_outliers),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun TraversalSample.directionLabel(): String =
    stringResource(if (direction > 0) R.string.segment_forward else R.string.segment_backward)

/**
 * How many points already share this end's name, so a junction name is visibly
 * one name over many corners rather than a lucky guess.
 */
@Composable
private fun placeNote(viewModel: AppViewModel, ref: PointRef?): String? {
    val place = ref?.let { viewModel.placeOf(it) } ?: return null
    return if (place.points.size > 1) {
        stringResource(R.string.place_member_count, place.points.size)
    } else {
        null
    }
}

/** One end of the segment, with the name the user gave it and a way to change it. */
@Composable
private fun EndpointRow(label: String, name: String?, note: String?, onName: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name ?: stringResource(R.string.point_unnamed),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onName) {
            Text(
                stringResource(
                    if (name == null) R.string.segment_name_add else R.string.action_edit,
                ),
            )
        }
    }
}

/** The sample count, plus how long the traversals were when we know it. */
@Composable
private fun sampleLine(stats: DurationStats): String {
    val count = pluralStringResource(R.plurals.samples_count, stats.count, stats.count)
    return if (stats.medianDistanceM > 0) count + " · " + formatDistance(stats.medianDistanceM) else count
}

/** One direction on its own: a divided road has two of these between the same crossings. */
@Composable
private fun DirectionHeadline(label: String, stats: DurationStats) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = sampleLine(stats),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (stats.count == 0) "—" else formatSeconds(stats.typicalMs),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        ConfidenceBadge(stats.confidence)
    }
}
