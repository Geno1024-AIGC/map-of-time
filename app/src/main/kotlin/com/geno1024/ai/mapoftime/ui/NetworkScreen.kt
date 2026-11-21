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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.geno1024.ai.mapoftime.analysis.NetworkGraph
import com.geno1024.ai.mapoftime.analysis.NetworkGraphs
import com.geno1024.ai.mapoftime.analysis.NetworkLink
import com.geno1024.ai.mapoftime.analysis.NetworkNode
import com.geno1024.ai.mapoftime.analysis.PlaceDraft
import com.geno1024.ai.mapoftime.analysis.PlaceDrafts
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.MapRing
import com.geno1024.ai.mapoftime.ui.map.TrackMap
import kotlinx.coroutines.launch

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
    // The picked junction and the mode filter are the list's context; restore
    // them along with its scroll offset. The review preview below is transient.
    var modeFilter by rememberSaveable(stateSaver = nullableEnumNameSaver(TripMode.entries.toTypedArray())) {
        mutableStateOf<TripMode?>(null)
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // The proposal being looked at: the map draws it before anything is saved,
    // because the mistake worth catching is a junction that ate its neighbour.
    var preview by remember { mutableStateOf<PlaceDraft?>(null) }
    // Where the tapped point is, and how far the preview currently reaches.
    // A 300 m interchange needs more than the default 150 m, but the default
    // has to stay tight or every dense crossing eats the next one.
    var previewAt by remember { mutableStateOf<LatLon?>(null) }
    var previewReach by remember { mutableStateOf(PlaceDrafts.SPAN_M) }
    // Points the user added or removed by tapping the map while reviewing.
    var previewManual by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var namingPlace by remember { mutableStateOf<PlaceDraft?>(null) }
    val scope = rememberCoroutineScope()
    // Drawn as a dot always; used by the camera only when there is no network
    // to frame yet, because this tab is about the roads, not about the phone.
    val here = rememberHere()

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
    val previewKeys = remember(preview) {
        preview?.refs?.mapTo(HashSet()) { it.key } ?: emptySet<String>()
    }
    // While picking points for one junction, the dots need to be tellable
    // apart: a bare "X" on all twenty of them is useless. Their auto-derived
    // side + road caption goes on the map, without asking for a single name.
    val markers = remember(graph, selectedKey, previewKeys, preview, viewModel.summaries, viewModel.places) {
        graph.nodes.map { node ->
            // Only the draft's own points get a preview-relative caption; a
            // far node in another junction has no business being described by
            // this preview's centre.
            val caption = if (preview != null && node.key in previewKeys) {
                viewModel.pointDetail(node.key, node.at, preview, modeFilter)?.label
            } else {
                null
            }
            MapMarker(
                at = node.at,
                emphasized = node.key == selectedKey || node.key in previewKeys,
                // Only names: a wall of node ids would hide the map. The
                // selected node still needs one even when it is unnamed.
                label = caption ?: if (node.key == selectedKey) node.name ?: node.key else node.name,
            )
        }
    }
    // Every junction already named gets a faint ring, so "what is one junction
    // and what is still loose" is visible at a glance; the proposal is bright.
    val rings = remember(viewModel.places, preview) {
        buildList {
            for (place in viewModel.places) {
                add(MapRing(place.at, place.radiusM.coerceAtLeast(PlaceDrafts.MIN_ADOPT_M)))
            }
            preview?.let { add(MapRing(it.center, it.adoptRadius(), active = true)) }
        }
    }
    // A proposal is ~100 m inside a city-wide network: without this the ring
    // would be a few pixels on a 250 dp map.
    // Leave room around the circle: judging whether it swallowed the next
    // crossing needs to see that crossing too, not just the claim.
    val focusBounds = remember(preview) {
        preview?.let { draft ->
            Geo.bounds(listOf(draft.center))?.expanded(draft.adoptRadius() + 60.0)
        }
    }
    // Normally a tap selects a node. While reviewing a junction it is a pick
    // instead: add that point to the draft, or take it back out, and redraw
    // the circle and count at once. Not remembered — it has to see the latest
    // preview and picks on every recomposition.
    val onMapTap = { index: Int ->
        val key = graph.nodes.getOrNull(index)?.key
        val draft = preview
        if (draft == null || key == null) {
            selectedKey = key
            preview = null
        } else if (key != draft.seed.key) {
            val included = draft.refs.any { it.key == key }
            previewManual = previewManual + (key to !included)
            preview = viewModel.placeDraft(draft.seed, previewAt, previewReach, previewManual)
        }
    }

    namingPlace?.let { draft ->
        PlaceNameDialog(
            initial = viewModel.placeName(draft.existingPlaceId) ?: selected?.name.orEmpty(),
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
                }
            },
            onSavePointOnly = { name ->
                namingPlace = null
                preview = null
                scope.launch { viewModel.renamePoint(draft.seed, name) }
            },
        )
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
                    onMarkerTap = onMapTap,
                    emptyHint = stringResource(R.string.network_empty_title),
                    rings = rings,
                    focusBounds = focusBounds,
                    here = here,
                )
            }
            // The review step: the ring is on the map, the numbers are here,
            // and nothing has been written yet.
            preview?.let { draft ->
                Text(
                    text = stringResource(R.string.place_preview_pick_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                val wider = PlaceDrafts.REACH_STEPS_M.firstOrNull { it > previewReach }
                PlacePreviewRow(
                    draft = draft,
                    onName = { namingPlace = draft },
                    onCancel = { preview = null },
                    onWider = wider?.let { reach ->
                        {
                            previewReach = reach
                            preview = viewModel.placeDraft(draft.seed, previewAt, reach, previewManual)
                        }
                    },
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
                        detail = viewModel.pointDetail(selected.key, selected.at, mode = modeFilter)?.label,
                        onOpenSegment = onOpenSegment,
                        onNamePlace = {
                            val ref = PointRef.parse(selected.key)
                            if (ref != null) {
                                // Show the proposal on the map first; naming it
                                // is the next tap, once the circle looks right.
                                previewAt = selected.at
                                previewReach = PlaceDrafts.SPAN_M
                                previewManual = emptyMap()
                                preview = viewModel.placeDraft(ref, selected.at, previewReach)
                            }
                        },
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
    /** Auto-derived "north side · People's Rd" caption, never a typed name. */
    detail: String?,
    onOpenSegment: (Long) -> Unit,
    onNamePlace: () -> Unit,
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
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Pill(
                text = stringResource(R.string.network_degree, node.degree),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // A node here is one point; the junction it belongs to may cover a
        // dozen. Naming it from the network is the natural place to do it.
        if (PointRef.parse(node.key) != null) {
            TextButton(onClick = onNamePlace) {
                Text(
                    stringResource(
                        if (node.name == null) R.string.place_name_action else R.string.action_edit,
                    ),
                )
            }
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
