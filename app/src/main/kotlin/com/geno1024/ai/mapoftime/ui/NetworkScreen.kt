package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.geno1024.ai.mapoftime.analysis.EdgeSummary
import com.geno1024.ai.mapoftime.analysis.NetworkGraph
import com.geno1024.ai.mapoftime.analysis.NetworkGraphs
import com.geno1024.ai.mapoftime.analysis.NetworkLink
import com.geno1024.ai.mapoftime.analysis.NetworkNode
import com.geno1024.ai.mapoftime.analysis.NetworkRoute
import com.geno1024.ai.mapoftime.analysis.RouteHop
import com.geno1024.ai.mapoftime.analysis.PlaceDraft
import com.geno1024.ai.mapoftime.analysis.PlaceDrafts
import com.geno1024.ai.mapoftime.core.DurationStats
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrafficContext
import com.geno1024.ai.mapoftime.core.TrafficDay
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.core.takeAt
import com.geno1024.ai.mapoftime.core.takeOrNull
import com.geno1024.ai.mapoftime.core.trafficDayOf
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.data.RoadNames
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.MapRing
import com.geno1024.ai.mapoftime.ui.map.TrackMap
import kotlinx.coroutines.launch

private enum class NetworkView { GRAPH, LIST }
private enum class EdgeSort { RECENT, SAMPLES, SLOWEST, NAME }

/**
 * The segments as a network: junctions are the dots, segments the lines
 * between them. Picking a junction answers the question the list cannot —
 * "where can I go from here, and how long does each way take".
 */
@Composable
fun NetworkScreen(
    viewModel: AppViewModel,
    onOpenEdge: (Long) -> Unit,
    /** One-shot request from a segment endpoint: preselect this node. */
    requestedNode: String? = null,
    onNodeRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    // The picked junction and the mode filter are the list's context; restore
    // them along with its scroll offset. The review preview below is transient.
    var modeFilter by rememberSaveable(stateSaver = nullableEnumNameSaver(TripMode.entries.toTypedArray())) {
        mutableStateOf<TripMode?>(null)
    }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Two-point query: the first map tap is the start, the second the end.
    // Kept next to the single-node selection but never mixed into it: picking
    // a route must not quietly change which junction the list is describing.
    var routeMode by rememberSaveable { mutableStateOf(false) }
    var routeFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var routeTo by rememberSaveable { mutableStateOf<String?>(null) }
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
    // Zoom to a node only when we were sent here from a segment. Ordinary taps
    // keep the whole network framed, so the next junction stays within reach.
    var focusNode by remember { mutableStateOf(false) }
    var viewMode by rememberSaveable(stateSaver = enumNameSaver(NetworkView.entries.toTypedArray())) {
        mutableStateOf(NetworkView.GRAPH)
    }
    var showNodes by rememberSaveable { mutableStateOf(true) }
    var showNodeLabels by rememberSaveable { mutableStateOf(true) }
    var edgeSort by rememberSaveable(stateSaver = enumNameSaver(EdgeSort.entries.toTypedArray())) {
        mutableStateOf(EdgeSort.SAMPLES)
    }
    var edgeQuery by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    // Drawn as a dot always; used by the camera only when there is no network
    // to frame yet, because this tab is about the roads, not about the phone.
    val here = rememberHere()

    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(requestedNode) {
        if (requestedNode == null) return@LaunchedEffect
        // A filter left over from an earlier visit could hide the very node we
        // were sent to; clear it so the jump always lands somewhere visible.
        viewMode = NetworkView.GRAPH
        modeFilter = null
        selectedKey = requestedNode
        routeMode = false
        routeFrom = null
        routeTo = null
        preview = null
        previewAt = null
        previewManual = emptyMap()
        focusNode = true
        onNodeRequestHandled()
    }

    val graph = remember(viewModel.summaries, modeFilter) {
        NetworkGraphs.build(
            viewModel.summaries.filter { modeFilter == null || it.edge.mode == modeFilter },
        )
    }
    val selected = graph.node(selectedKey)
    val route = remember(graph, routeFrom, routeTo, routeMode) {
        if (routeMode && routeFrom != null && routeTo != null) {
            graph.route(requireNotNull(routeFrom), requireNotNull(routeTo))
        } else {
            null
        }
    }
    val routeKeys = remember(route) { route?.nodeKeys?.toSet().orEmpty() }

    val nowMs = System.currentTimeMillis()
    val now = TrafficContext.of(epochMs = nowMs, calendar = settings.holidayCalendar)
    val fridayRush = trafficDayOf(nowMs, calendar = settings.holidayCalendar) == TrafficDay.FRIDAY_RUSH
    val edgeSummaries = remember(viewModel.summaries, modeFilter, edgeSort, edgeQuery) {
        val text = edgeQuery.trim()
        val filtered = viewModel.summaries
            .filter { modeFilter == null || it.edge.mode == modeFilter }
            .filter {
                text.isEmpty() ||
                    it.edge.displayName.contains(text, ignoreCase = true) ||
                    it.edge.key.contains(text, ignoreCase = true)
            }
        when (edgeSort) {
            EdgeSort.SAMPLES -> filtered.sortedWith(compareByDescending<EdgeSummary> { it.sampleCount }.thenBy { it.edge.displayName })
            EdgeSort.RECENT -> filtered.sortedByDescending { it.edge.lastSeen }
            EdgeSort.SLOWEST -> filtered.sortedByDescending { it.combined.medianMs }
            EdgeSort.NAME -> filtered.sortedBy { it.edge.displayName.lowercase() }
        }
    }

    // One track per segment; shared junctions produce identical coordinates,
    // so the lines meet exactly where the node dots are.
    val tracks = remember(graph) {
        graph.edges.map { listOf(it.edge.from, it.edge.to) }
    }
    val routeHighlight = remember(graph, route) {
        route?.nodeKeys?.mapNotNull { graph.node(it)?.at }?.takeIf { it.size >= 2 }
    }
    val previewKeys = remember(preview) {
        preview?.refs?.mapTo(HashSet()) { it.key } ?: emptySet<String>()
    }
    // While picking points for one junction, the dots need to be tellable
    // apart: a bare "X" on all twenty of them is useless. Their auto-derived
    // side + road caption goes on the map, without asking for a single name.
    val routeFromPrefix = stringResource(R.string.network_route_from_prefix)
    val routeToPrefix = stringResource(R.string.network_route_to_prefix)
    val markers = remember(graph, selectedKey, previewKeys, preview, viewModel.summaries, viewModel.places, showNodes, showNodeLabels, routeMode, routeFrom, routeTo, routeKeys, routeFromPrefix, routeToPrefix) {
        if (!showNodes) emptyList()
        else graph.nodes.map { node ->
            // Only the draft's own points get a preview-relative caption; a
            // far node in another junction has no business being described by
            // this preview's centre.
            val caption = if (preview != null && node.key in previewKeys) {
                viewModel.pointDetail(node.key, node.at, preview, modeFilter)?.label
            } else {
                null
            }
            val baseLabel = caption ?: if (node.key == selectedKey) node.name ?: node.key else node.name
            val labelled = when {
                !showNodeLabels -> null
                routeMode && node.key == routeFrom -> routeFromPrefix + " · " + (baseLabel ?: node.key)
                routeMode && node.key == routeTo -> routeToPrefix + " · " + (baseLabel ?: node.key)
                else -> baseLabel
            }
            MapMarker(
                at = node.at,
                emphasized = node.key == selectedKey || node.key in previewKeys || node.key in routeKeys,
                // Only names: a wall of node ids would hide the map. The
                // selected node still needs one even when it is unnamed.
                label = labelled,
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
    // When arriving from a segment, look at that node instead of the whole city.
    val focusBounds = remember(preview, focusNode, selected, routeHighlight) {
        preview?.let { draft ->
            Geo.bounds(listOf(draft.center))?.expanded(draft.adoptRadius() + 60.0)
        } ?: routeHighlight?.let {
            Geo.bounds(it)?.expanded(60.0)
        } ?: if (focusNode) {
            selected?.at?.let { Geo.bounds(listOf(it))?.expanded(80.0) }
        } else {
            null
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
            if (routeMode && key != null) {
                // First tap starts over; the second finishes. A third tap is a
                // new query rather than a silent change to an old one.
                if (routeFrom == null || routeTo != null) {
                    routeFrom = key
                    routeTo = null
                } else {
                    routeTo = key
                }
            } else {
                selectedKey = key
            }
            focusNode = false
            preview = null
        } else if (key != draft.seed.key) {
            val included = draft.refs.any { it.key == key }
            previewManual = previewManual + (key to !included)
            preview = viewModel.placeDraft(draft.seed, previewAt, previewReach, previewManual)
        }
    }

    namingPlace?.let { draft ->
        val initialRoadsSet = viewModel.roadsAt(draft.refs)
        val rawInitial = viewModel.placeName(draft.existingPlaceId) ?: selected?.name.orEmpty()
        val physicalInitial = if (RoadNames.isComposite(rawInitial) && rawInitial == RoadNames.join(initialRoadsSet)) "" else rawInitial
        PlaceNameDialog(
            initial = physicalInitial,
            pointCount = draft.count,
            spanM = draft.spanM,
            named = draft.existingPlaceId != null,
            overriddenCount = viewModel.overriddenMembers(draft),
            roadCandidates = viewModel.pointRoadCandidatesFor(draft.refs),
            initialRoads = initialRoadsSet,
            initialKind = viewModel.placeKind(draft.existingPlaceId),
            onDismiss = {
                namingPlace = null
                preview = null
            },
            onSave = { name, takeOverNames, roads, kind ->
                namingPlace = null
                preview = null
                scope.launch {
                    viewModel.namePlace(
                        draft,
                        name,
                        clearMemberNames = takeOverNames,
                        roads = roads,
                        kind = kind,
                    )
                }
            },
            onSavePointOnly = { name, roads, _ ->
                namingPlace = null
                preview = null
                scope.launch { viewModel.renamePoint(draft.seed, name, roads = roads) }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.network_title),
            subtitle = when (viewMode) {
                NetworkView.GRAPH -> if (graph.edges.isEmpty()) null
                else if (graph.parts > 1) stringResource(R.string.network_summary_parts, graph.nodes.size, graph.edges.size, graph.parts)
                else stringResource(R.string.network_summary, graph.nodes.size, graph.edges.size)
                NetworkView.LIST -> edgeSummaries.size.toString().takeIf { edgeSummaries.isNotEmpty() }
            },
        )

        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = viewMode == NetworkView.GRAPH,
                    onClick = { viewMode = NetworkView.GRAPH },
                    label = { Text(stringResource(R.string.network_view_graph)) },
                )
                FilterChip(
                    selected = viewMode == NetworkView.LIST,
                    onClick = {
                        viewMode = NetworkView.LIST
                        routeMode = false
                    },
                    label = { Text(stringResource(R.string.network_view_list)) },
                )
                if (viewMode == NetworkView.GRAPH) {
                    FilterChip(
                        selected = routeMode,
                        onClick = { routeMode = !routeMode },
                        label = { Text(stringResource(R.string.network_route_mode)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ModeFilter(
                selected = modeFilter,
                onSelect = {
                    modeFilter = it
                    focusNode = false
                    routeFrom = null
                    routeTo = null
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (viewMode == NetworkView.LIST) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = edgeQuery,
                    onValueChange = { edgeQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.edges_search)) },
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortChip(EdgeSort.SAMPLES, edgeSort, R.string.edges_sort_samples) { edgeSort = it }
                    SortChip(EdgeSort.RECENT, edgeSort, R.string.edges_sort_recent) { edgeSort = it }
                    SortChip(EdgeSort.SLOWEST, edgeSort, R.string.edges_sort_slowest) { edgeSort = it }
                    SortChip(EdgeSort.NAME, edgeSort, R.string.edges_sort_name) { edgeSort = it }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (edgeSummaries.isEmpty()) {
                EmptyState(
                    title = stringResource(if (viewModel.summaries.isEmpty()) R.string.edges_empty_title else R.string.edges_no_match_title),
                    body = stringResource(if (viewModel.summaries.isEmpty()) R.string.edges_empty_body else R.string.edges_no_match_body),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(edgeSummaries, key = { it.edge.id }) { summary ->
                        EdgeCard(summary = summary, now = now, fridayRush = fridayRush, onClick = { onOpenEdge(summary.edge.id) })
                    }
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
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
                        onTrackTap = { index ->
                            if (preview == null) graph.edges.getOrNull(index)?.let { onOpenEdge(it.edge.id) }
                        },
                        emptyHint = stringResource(R.string.network_empty_title),
                        highlight = routeHighlight,
                        rings = rings,
                        focusBounds = focusBounds,
                        here = here,
                    )
                    Column(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MapOverlayToggle(
                            selected = showNodes,
                            onClick = { showNodes = !showNodes },
                            label = stringResource(R.string.network_show_nodes),
                        )
                        if (showNodes) {
                            MapOverlayToggle(
                                selected = showNodeLabels,
                                onClick = { showNodeLabels = !showNodeLabels },
                                label = stringResource(R.string.network_show_node_labels),
                            )
                        }
                    }
                }
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
                    text = stringResource(
                        if (routeMode) R.string.network_route_hint else R.string.network_hint,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (graph.edges.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.network_empty_title),
                    body = stringResource(R.string.network_empty_body),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                ) {
                    item {
                        when {
                            routeMode -> RouteCard(
                                graph = graph,
                                from = graph.node(routeFrom),
                                to = graph.node(routeTo),
                                route = route,
                                samePoint = routeFrom != null && routeFrom == routeTo,
                                onReset = {
                                    routeFrom = null
                                    routeTo = null
                                },
                                onOpenEdge = onOpenEdge,
                            )
                            selected == null -> SectionCard {
                                Text(
                                    text = stringResource(R.string.network_pick_node),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> NodeCard(
                                node = selected,
                                graph = graph,
                                detail = viewModel.pointDetail(selected.key, selected.at, mode = modeFilter)?.label,
                                onOpenEdge = onOpenEdge,
                                onStartRoute = { key ->
                                    routeMode = true
                                    routeFrom = key
                                    routeTo = null
                                },
                                onNamePlace = {
                                    val ref = PointRef.parse(selected.key)
                                    focusNode = false
                                    if (ref != null) {
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
    }
}

/** Two chosen nodes and the cheapest typical-time path between them. */
@Composable
private fun RouteCard(
    graph: NetworkGraph,
    from: NetworkNode?,
    to: NetworkNode?,
    route: NetworkRoute?,
    samePoint: Boolean,
    onReset: () -> Unit,
    onOpenEdge: (Long) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.network_route_title),
        subtitle = stringResource(R.string.network_route_note),
    ) {
        val fromLabel = from?.name ?: from?.key ?: "—"
        val toLabel = to?.name ?: to?.key ?: "—"
        Text(
            text = stringResource(R.string.network_route_endpoints, fromLabel, toLabel),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            samePoint -> Text(
                text = stringResource(R.string.network_route_same),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            from == null -> Text(
                text = stringResource(R.string.network_route_pick_start),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            to == null -> Text(
                text = stringResource(R.string.network_route_pick_end, fromLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            route == null -> Text(
                text = stringResource(R.string.network_route_no_path),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = formatSeconds(route.typicalMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatDistance(route.distanceM) + " · " +
                        stringResource(R.string.network_route_hops, route.hops.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (route.estimatedHops > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.network_route_estimated, route.estimatedHops),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ThinDivider()
                route.hops.forEachIndexed { index, hop ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    RouteHopRow(
                        index = index + 1,
                        hop = hop,
                        graph = graph,
                        onClick = { onOpenEdge(hop.link.summary.edge.id) },
                    )
                }
            }
        }
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.network_route_reset))
        }
    }
}

@Composable
private fun RouteHopRow(
    index: Int,
    hop: RouteHop,
    graph: NetworkGraph,
    onClick: () -> Unit,
) {
    val from = graph.node(hop.fromKey)
    val to = graph.node(hop.toKey)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "$index. " + hop.link.summary.edge.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = (from?.name ?: hop.fromKey) + " → " + (to?.name ?: hop.toKey),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatSeconds(hop.typicalMs),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NodeCard(
    node: NetworkNode,
    graph: NetworkGraph,
    /** Auto-derived "north side · People's Rd" caption, never a typed name. */
    detail: String?,
    onOpenEdge: (Long) -> Unit,
    onStartRoute: (String) -> Unit,
    onNamePlace: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    // An unnamed crossing is not "unnamed": the roads through
                    // it say what it is, once those roads have been named.
                    text = node.name ?: detail ?: stringResource(R.string.network_node_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = node.key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detail != null && node.name != null) {
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
        TextButton(onClick = { onStartRoute(node.key) }) {
            Text(stringResource(R.string.network_route_from_here))
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
                onClick = { onOpenEdge(link.summary.edge.id) },
            )
        }
    }
}

/** One segment leaving the selected junction: away first, back second. */
@Composable
private fun LinkRow(link: NetworkLink, other: NetworkNode?, onClick: () -> Unit) {
    val segment = link.summary.edge
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

private val EdgeSummary.displayName: String
    get() = edge.displayName

@Composable
private fun SortChip(
    value: EdgeSort,
    selected: EdgeSort,
    labelRes: Int,
    onSelect: (EdgeSort) -> Unit,
) {
    FilterChip(
        selected = value == selected,
        onClick = { onSelect(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun EdgeCard(
    summary: EdgeSummary,
    now: TrafficContext,
    fridayRush: Boolean,
    onClick: () -> Unit,
) {
    val edge = summary.edge
    val divided = summary.forward.count > 0 && summary.backward.count > 0
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

@Composable
private fun MapOverlayToggle(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                color = if (selected) colors.primary else colors.surface.copy(alpha = 0.82f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }
}
