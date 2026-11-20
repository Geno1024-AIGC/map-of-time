package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.NetworkGraph
import com.geno1024.ai.mapoftime.analysis.NetworkGraphs
import com.geno1024.ai.mapoftime.analysis.NetworkLink
import com.geno1024.ai.mapoftime.analysis.NetworkNode
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.TrackMap

/**
 * The segments as a network: junctions are the dots, segments the lines
 * between them. Picking a junction answers the question the list cannot —
 * "where can I go from here, and how long does each way take".
 */
@Composable
fun NetworkScreen(
    viewModel: AppViewModel,
    onOpenSegment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    var modeFilter by remember { mutableStateOf<TripMode?>(null) }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val graph = remember(viewModel.summaries, modeFilter) {
        NetworkGraphs.build(
            viewModel.summaries.filter { modeFilter == null || it.segment.mode == modeFilter },
        )
    }
    val selected = graph.node(selectedKey)

    // One track per segment; shared junctions produce identical coordinates,
    // so the lines meet exactly where the node dots are.
    val tracks = remember(graph) {
        graph.segments.map { listOf(it.segment.from, it.segment.to) }
    }
    val markers = remember(graph, selectedKey) {
        graph.nodes.map { node ->
            MapMarker(
                at = node.at,
                emphasized = node.key == selectedKey,
                // Only names: a wall of node ids would hide the map. The
                // selected node still needs one even when it is unnamed.
                label = if (node.key == selectedKey) node.name ?: node.key else node.name,
            )
        }
    }
    val selectNode = remember(graph) {
        { index: Int -> selectedKey = graph.nodes.getOrNull(index)?.key }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.network_title),
            subtitle = if (graph.segments.isEmpty()) {
                null
            } else if (graph.parts > 1) {
                stringResource(
                    R.string.network_summary_parts,
                    graph.nodes.size,
                    graph.segments.size,
                    graph.parts,
                )
            } else {
                stringResource(
                    R.string.network_summary,
                    graph.nodes.size,
                    graph.segments.size,
                )
            },
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            ModeFilter(selected = modeFilter, onSelect = { modeFilter = it })
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(250.dp)) {
                TrackMap(
                    tracks = tracks,
                    modifier = Modifier.fillMaxSize(),
                    markers = markers,
                    tilesEnabled = settings.mapTiles,
                    basemap = settings.basemap,
                    customTileUrl = settings.customTileUrl,
                    customTilesGcj02 = settings.customTilesGcj02,
                    onMarkerTap = selectNode,
                    emptyHint = stringResource(R.string.network_empty_title),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.network_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (graph.segments.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.network_empty_title),
                body = stringResource(R.string.network_empty_body),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                if (selected == null) {
                    SectionCard {
                        Text(
                            text = stringResource(R.string.network_pick_node),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    NodeCard(
                        node = selected,
                        graph = graph,
                        onOpenSegment = onOpenSegment,
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeCard(
    node: NetworkNode,
    graph: NetworkGraph,
    onOpenSegment: (Long) -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = node.name ?: stringResource(R.string.network_node_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = node.key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(
                text = stringResource(R.string.network_degree, node.degree),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ThinDivider()
        node.links.forEachIndexed { index, link ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            LinkRow(
                link = link,
                other = graph.node(link.otherKey),
                onClick = { onOpenSegment(link.summary.segment.id) },
            )
        }
    }
}

/** One segment leaving the selected junction: away first, back second. */
@Composable
private fun LinkRow(link: NetworkLink, other: NetworkNode?, onClick: () -> Unit) {
    val segment = link.summary.segment
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = segment.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = modeLabel(segment.mode) + " · " +
                    (other?.name ?: stringResource(R.string.network_other_unnamed)) + " · " +
                    pluralStringResource(
                        R.plurals.samples_count,
                        link.summary.sampleCount,
                        link.summary.sampleCount,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = stringResource(R.string.network_out_time, typical(link.outbound)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.network_back_time, typical(link.inbound)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun typical(stats: DurationStats): String =
    if (stats.count == 0) "—" else formatSeconds(stats.typicalMs)
