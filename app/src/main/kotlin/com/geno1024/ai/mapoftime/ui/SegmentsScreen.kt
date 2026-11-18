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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.SegmentSummary
import com.geno1024.ai.mapoftime.core.TripMode

@Composable
fun SegmentsScreen(
    viewModel: AppViewModel,
    onOpenSegment: (Long) -> Unit,
) {
    var modeFilter by remember { mutableStateOf<TripMode?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val summaries = remember(viewModel.summaries, modeFilter) {
        if (modeFilter == null) {
            viewModel.summaries
        } else {
            viewModel.summaries.filter { it.segment.mode == modeFilter }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.segments_title),
            subtitle = if (summaries.isEmpty()) null else summaries.size.toString(),
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            ModeFilter(selected = modeFilter, onSelect = { modeFilter = it })
            Spacer(Modifier.height(12.dp))
        }

        if (summaries.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.segments_empty_title),
                body = stringResource(R.string.segments_empty_body),
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
private fun SegmentCard(summary: SegmentSummary, onClick: () -> Unit) {
    val segment = summary.segment
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = segment.name ?: segment.key,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = modeLabel(segment.mode) + " · " +
                        stringResource(R.string.samples_count, summary.sampleCount) + " · " +
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
