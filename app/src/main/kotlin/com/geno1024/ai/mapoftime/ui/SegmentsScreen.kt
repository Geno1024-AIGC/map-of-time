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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.SegmentSummary
import com.geno1024.ai.mapoftime.core.TripMode

/** How the segment list is ordered. Every one is a question the user asks. */
private enum class SegmentSort { RECENT, SAMPLES, SLOWEST, NAME }

@Composable
fun SegmentsScreen(
    viewModel: AppViewModel,
    onOpenSegment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var modeFilter by remember { mutableStateOf<TripMode?>(null) }
    var sort by remember { mutableStateOf(SegmentSort.SAMPLES) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }

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
            SegmentSort.SAMPLES -> filtered.sortedWith(
                compareByDescending<SegmentSummary> { it.sampleCount }.thenBy { it.displayName },
            )
            SegmentSort.RECENT -> filtered.sortedByDescending { it.segment.lastSeen }
            // Slowest first: the question is almost always "where do I lose time".
            SegmentSort.SLOWEST -> filtered.sortedByDescending { it.combined.medianMs }
            SegmentSort.NAME -> filtered.sortedBy { it.displayName.lowercase() }
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.segments_title),
            subtitle = if (summaries.isEmpty()) null else summaries.size.toString(),
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.segments_search)) },
            )
            Spacer(Modifier.height(8.dp))
            ModeFilter(selected = modeFilter, onSelect = { modeFilter = it })
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip(SegmentSort.SAMPLES, sort, R.string.segments_sort_samples) { sort = it }
                SortChip(SegmentSort.RECENT, sort, R.string.segments_sort_recent) { sort = it }
                SortChip(SegmentSort.SLOWEST, sort, R.string.segments_sort_slowest) { sort = it }
                SortChip(SegmentSort.NAME, sort, R.string.segments_sort_name) { sort = it }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (summaries.isEmpty()) {
            EmptyState(
                title = stringResource(
                    if (viewModel.summaries.isEmpty()) R.string.segments_empty_title else R.string.segments_no_match_title,
                ),
                body = stringResource(
                    if (viewModel.summaries.isEmpty()) R.string.segments_empty_body else R.string.segments_no_match_body,
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
                SegmentCard(summary = summary, onClick = { onOpenSegment(summary.segment.id) })
            }
        }
    }
}

@Composable
private fun SortChip(
    value: SegmentSort,
    selected: SegmentSort,
    label: Int,
    onSelect: (SegmentSort) -> Unit,
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(stringResource(label)) },
    )
}

/** The human name when OSM gave one, the key otherwise; also used for search. */
private val SegmentSummary.displayName: String
    get() = segment.name ?: segment.key

@Composable
private fun SegmentCard(summary: SegmentSummary, onClick: () -> Unit) {
    val segment = summary.segment
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = summary.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = modeLabel(segment.mode) + " · " +
                        pluralStringResource(R.plurals.samples_count, summary.sampleCount, summary.sampleCount) + " · " +
                        formatDistance(segment.lengthM),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MatchBadge(segment.identity)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (summary.sampleCount == 0) "—" else formatSeconds(summary.combined.typicalMs),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.typical_time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConfidenceBadge(summary.combined.confidence)
        }
    }
}
