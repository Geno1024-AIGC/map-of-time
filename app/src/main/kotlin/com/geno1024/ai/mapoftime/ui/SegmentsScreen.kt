package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.geno1024.ai.mapoftime.analysis.EdgeSummary
import com.geno1024.ai.mapoftime.core.TrafficContext
import com.geno1024.ai.mapoftime.core.TrafficDay
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.core.takeAt
import com.geno1024.ai.mapoftime.core.takeOrNull
import com.geno1024.ai.mapoftime.core.trafficDayOf

/** How the segment list is ordered. Every one is a question the user asks. */
private enum class EdgeSort { RECENT, SAMPLES, SLOWEST, NAME }

@Composable
fun SegmentsScreen(
    viewModel: AppViewModel,
    onOpenEdge: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // What the user is looking at travels with the scroll offset: opening a
    // segment and pressing Back restores the same filtered, sorted list at the
    // same row. Without these, the restored offset would land in a list that
    // had quietly reverted to the default order.
    var modeFilter by rememberSaveable(stateSaver = nullableEnumNameSaver(TripMode.entries.toTypedArray())) {
        mutableStateOf<TripMode?>(null)
    }
    var sort by rememberSaveable(stateSaver = enumNameSaver(EdgeSort.entries.toTypedArray())) {
        mutableStateOf(EdgeSort.SAMPLES)
    }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Which part of the week it is, for the "now" number on each card. The
    // card would keep a stale label until the next recomposition if this were
    // remembered, and reading a clock in composition is cheaper than a ticker.
    // The calendar matters here too: a 调休 Saturday is a working day.
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    val nowMs = System.currentTimeMillis()
    val now = TrafficContext.of(epochMs = nowMs, calendar = settings.holidayCalendar)
    // Friday 17:00–19:00 has its own bucket, so "now" has to leave the table
    // for it; otherwise the number would be the weekday peak minus Fridays.
    val fridayRush = trafficDayOf(nowMs, calendar = settings.holidayCalendar) == TrafficDay.FRIDAY_RUSH

    val summaries = remember(viewModel.summaries, modeFilter, sort, query) {
        val text = query.trim()
        val filtered = viewModel.summaries
            .filter { modeFilter == null || it.segment.mode == modeFilter }
            .filter {
                text.isEmpty() ||
                    it.displayName.contains(text, ignoreCase = true) ||
                    it.segment.key.contains(text, ignoreCase = true)
            }
        when (sort) {
            // Most used first: the segments that actually accumulate samples.
            EdgeSort.SAMPLES -> filtered.sortedWith(
                compareByDescending<EdgeSummary> { it.sampleCount }.thenBy { it.displayName },
            )
            EdgeSort.RECENT -> filtered.sortedByDescending { it.segment.lastSeen }
            // Slowest first: the question is almost always "where do I lose time".
            EdgeSort.SLOWEST -> filtered.sortedByDescending { it.combined.medianMs }
            EdgeSort.NAME -> filtered.sortedBy { it.displayName.lowercase() }
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.edges_title),
            subtitle = if (summaries.isEmpty()) null else summaries.size.toString(),
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.edges_search)) },
            )
            Spacer(Modifier.height(8.dp))
            ModeFilter(selected = modeFilter, onSelect = { modeFilter = it })
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip(EdgeSort.SAMPLES, sort, R.string.edges_sort_samples) { sort = it }
                SortChip(EdgeSort.RECENT, sort, R.string.edges_sort_recent) { sort = it }
                SortChip(EdgeSort.SLOWEST, sort, R.string.edges_sort_slowest) { sort = it }
                SortChip(EdgeSort.NAME, sort, R.string.edges_sort_name) { sort = it }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (summaries.isEmpty()) {
            EmptyState(
                title = stringResource(
                    if (viewModel.summaries.isEmpty()) R.string.edges_empty_title else R.string.edges_no_match_title,
                ),
                body = stringResource(
                    if (viewModel.summaries.isEmpty()) R.string.edges_empty_body else R.string.edges_no_match_body,
                ),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(summaries, key = { it.segment.id }) { summary ->
                EdgeCard(
                    summary = summary,
                    now = now,
                    fridayRush = fridayRush,
                    onClick = { onOpenEdge(summary.edge.id) },
                )
            }
        }
    }
}

@Composable
private fun SortChip(
    value: EdgeSort,
    selected: EdgeSort,
    label: Int,
    onSelect: (EdgeSort) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(stringResource(label)) },
    )
}

/** The human name when OSM gave one, the key otherwise; also used for search. */
private val EdgeSummary.displayName: String
    get() = edge.displayName

@Composable
private fun EdgeCard(
    summary: EdgeSummary,
    now: TrafficContext,
    fridayRush: Boolean,
    onClick: () -> Unit,
) {
    val edge = summary.edge
    // Two carriageways, two numbers: the combined median of a divided road
    // describes neither side. With one direction only, the median is that
    // side and the big number stays.
    val divided = summary.forward.count > 0 && summary.backward.count > 0
    // A bucket of one or two trips is not a typical time, so it falls back to
    // the pooled median. For a divided road both sides have to come from the
    // same bucket, or the pair stops being comparable — which is the only
    // reason it is shown as a pair.
    val forwardNow = if (fridayRush) summary.forwardFridayRush.takeOrNull() else summary.forwardTakes.takeAt(now)
    val backwardNow = if (fridayRush) summary.backwardFridayRush.takeOrNull() else summary.backwardTakes.takeAt(now)
    val singleNow = if (summary.forward.count > 0) forwardNow else backwardNow
    val specific = if (divided) forwardNow != null && backwardNow != null else singleNow != null
    val nowLabel = if (fridayRush) {
        stringResource(R.string.day_friday_rush)
    } else {
        dayKindLabel(now.day) + " · " + peakLabel(now.peak)
    }

    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = summary.edge.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = modeLabel(edge.mode) + " · " +
                        pluralStringResource(R.plurals.samples_count, summary.sampleCount, summary.sampleCount) + " · " +
                        formatDistance(summary.combined.medianDistanceM.takeIf { it > 0 } ?: edge.lengthM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MatchBadge(edge.identity)
        }
        Spacer(Modifier.height(10.dp))
        if (divided) {
            Row(verticalAlignment = Alignment.Bottom) {
                val from = stringResource(R.string.edge_from)
                val to = stringResource(R.string.edge_to)
                DirectionTime(
                    label = edge.forwardName ?: "$from → $to",
                    ms = forwardNow?.medianMs ?: summary.forward.typicalMs,
                    modifier = Modifier.weight(1f),
                )
                DirectionTime(
                    label = edge.backwardName ?: "$to → $from",
                    ms = backwardNow?.medianMs ?: summary.backward.typicalMs,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                ConfidenceBadge(summary.combined.confidence)
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (summary.sampleCount == 0) "—" else
                        formatSeconds(singleNow?.medianMs ?: summary.combined.typicalMs),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ConfidenceBadge(summary.combined.confidence)
            }
        }
        if (summary.sampleCount > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (specific) {
                    stringResource(R.string.typical_now, nowLabel)
                } else {
                    stringResource(R.string.typical_now_thin, nowLabel)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One direction's typical time on the list card. */
@Composable
private fun DirectionTime(label: String, ms: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = formatSeconds(ms),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
