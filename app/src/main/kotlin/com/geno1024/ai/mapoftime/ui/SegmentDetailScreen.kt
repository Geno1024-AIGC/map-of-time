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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.core.TraversalSample
import com.geno1024.ai.mapoftime.data.Leg
import com.geno1024.ai.mapoftime.data.Segment
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.TrackMap

@Composable
fun SegmentDetailScreen(
    viewModel: AppViewModel,
    segmentId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    var segment by remember(segmentId) { mutableStateOf<Segment?>(null) }
    var samples by remember(segmentId) { mutableStateOf<List<TraversalSample>>(emptyList()) }
    var legs by remember(segmentId) { mutableStateOf<List<Leg>>(emptyList()) }

    LaunchedEffect(segmentId) {
        segment = viewModel.segment(segmentId)
        samples = viewModel.samples(segmentId)
        legs = viewModel.legsForSegment(segmentId)
    }

    val stats = remember(samples) { DurationStats.of(samples) }
    val forward = remember(samples) { DurationStats.of(samples.filter { it.direction > 0 }) }
    val backward = remember(samples) { DurationStats.of(samples.filter { it.direction < 0 }) }

    val tracks = remember(legs) {
        legs.map { Polyline.decode(it.polyline) }.filter { it.size >= 2 }
    }
    val markers = remember(segment) {
        segment?.let { listOf(MapMarker(it.from), MapMarker(it.to, emphasized = true)) }.orEmpty()
    }

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = segment?.name ?: segment?.key ?: stringResource(R.string.segments_title),
            subtitle = segment?.let { modeLabel(it.mode) + " · " + formatDistance(it.lengthM) },
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
                )
            }

            item {
                SectionCard(title = stringResource(R.string.segment_stats_title)) {
                    if (segment != null) {
                        MatchBadge(segment!!.identity)
                        Spacer(Modifier.height(10.dp))
                    }
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
                            label = stringResource(R.string.segment_forward),
                            stats = forward,
                            reference = stats.medianMs,
                        )
                        Spacer(Modifier.height(8.dp))
                        DirectionRow(
                            label = stringResource(R.string.segment_backward),
                            stats = backward,
                            reference = stats.medianMs,
                        )
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

@Composable
private fun DirectionRow(label: String, stats: DurationStats, reference: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = pluralStringResource(R.plurals.samples_count, stats.count, stats.count),
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
