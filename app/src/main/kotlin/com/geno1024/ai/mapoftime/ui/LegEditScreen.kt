package com.geno1024.ai.mapoftime.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.PlaceDraft
import com.geno1024.ai.mapoftime.analysis.PlaceDrafts
import com.geno1024.ai.mapoftime.core.Polyline
import com.geno1024.ai.mapoftime.data.Edge
import com.geno1024.ai.mapoftime.data.Leg
import com.geno1024.ai.mapoftime.data.PointRef
import com.geno1024.ai.mapoftime.data.RoadNames
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.MapRing
import com.geno1024.ai.mapoftime.ui.map.TrackMap
import kotlinx.coroutines.launch

@Composable
fun LegEditScreen(
    viewModel: AppViewModel,
    tripId: Long,
    legId: Long,
    onBack: () -> Unit,
    onOpenNode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var leg by remember(tripId, legId) { mutableStateOf<Leg?>(null) }
    var edge by remember(tripId, legId) { mutableStateOf<Edge?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var bindingRoad by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PlaceDraft?>(null) }
    var previewAt by remember { mutableStateOf<com.geno1024.ai.mapoftime.core.LatLon?>(null) }
    var previewReach by remember { mutableStateOf(PlaceDrafts.SPAN_M) }
    var namingPlace by remember { mutableStateOf<PlaceDraft?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(tripId, legId, reload) {
        leg = viewModel.legs(tripId).firstOrNull { it.id == legId }
        edge = leg?.edgeId?.let { viewModel.edge(it) }
    }

    val track = remember(leg) { leg?.let { Polyline.decode(it.polyline) }?.takeIf { it.size >= 2 }.orEmpty() }
    val markers = remember(edge, leg) {
        val e = edge ?: return@remember emptyList()
        if (leg?.partial == true) emptyList()
        else listOf(
            MapMarker(e.from, label = e.fromName ?: "A", emphasized = true),
            MapMarker(e.to, label = e.toName ?: "B", emphasized = true),
        )
    }
    val rings = remember(preview) {
        preview?.let { draft -> listOf(MapRing(draft.center, draft.adoptRadius(), active = true)) }.orEmpty()
    }

    val bindingEdge = edge
    if (bindingRoad && bindingEdge != null) {
        EdgeRoadsDialog(
            roads = viewModel.roads,
            selected = bindingEdge.roadIds.toSet(),
            onDismiss = { bindingRoad = false },
            onSave = { ids ->
                bindingRoad = false
                scope.launch {
                    viewModel.setEdgeRoads(bindingEdge.id, ids)
                    edge = viewModel.edge(bindingEdge.id)
                }
            },
        )
    }

    val renamingEdge = edge
    if (renaming && renamingEdge != null) {
        EdgeNameDialog(
            initial = renamingEdge.userName.orEmpty(),
            reuse = viewModel.roadNames,
            onDismiss = { renaming = false },
            onSave = { name ->
                renaming = false
                scope.launch {
                    viewModel.renameEdge(renamingEdge.id, name)
                    edge = viewModel.edge(renamingEdge.id)
                    reload += 1
                }
            },
        )
    }

    namingPlace?.let { draft ->
        val initialRoadsSet = viewModel.roadsAt(draft.refs)
        val rawInitial = viewModel.placeName(draft.existingPlaceId) ?: draft.seed.key.let { key ->
            when (key) {
                edge?.fromKey -> edge?.fromName
                edge?.toKey -> edge?.toName
                else -> null
            }
        }.orEmpty()
        val physicalInitial = if (RoadNames.isComposite(rawInitial) && rawInitial == RoadNames.join(initialRoadsSet)) "" else rawInitial
        PlaceNameDialog(
            initial = physicalInitial,
            pointCount = draft.count,
            spanM = draft.spanM,
            named = draft.existingPlaceId != null,
            overriddenCount = viewModel.overriddenMembers(draft),
            roadCandidates = viewModel.pointRoadCandidatesFor(draft.refs),
            initialRoads = initialRoadsSet,
            onDismiss = { namingPlace = null; preview = null },
            onSave = { name, takeOverNames, roads ->
                namingPlace = null
                preview = null
                scope.launch {
                    viewModel.namePlace(draft, name, clearMemberNames = takeOverNames, roads = roads)
                    edge = edge?.id?.let { viewModel.edge(it) }
                    reload += 1
                }
            },
            onSavePointOnly = { name, roads ->
                namingPlace = null
                preview = null
                scope.launch {
                    viewModel.renamePoint(draft.seed, name, roads = roads)
                    edge = edge?.id?.let { viewModel.edge(it) }
                    reload += 1
                }
            },
        )
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.leg_edit_page_title),
            subtitle = edge?.displayName ?: leg?.label,
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    TrackMap(
                        tracks = if (track.isEmpty()) emptyList() else listOf(track),
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        markers = markers,
                        tilesEnabled = settings.mapTiles,
                        basemap = settings.basemap,
                        customTileUrl = settings.customTileUrl,
                        customTilesGcj02 = settings.customTilesGcj02,
                        rings = rings,
                    )
                    preview?.let { draft ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.place_preview_pick_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        PlacePreviewRow(
                            draft = draft,
                            onName = { namingPlace = draft },
                            onCancel = { preview = null },
                            onWider = PlaceDrafts.REACH_STEPS_M.firstOrNull { it > previewReach }?.let { reach ->
                                {
                                    previewReach = reach
                                    preview = viewModel.placeDraft(draft.seed, previewAt, reach)
                                }
                            },
                        )
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.leg_edit_impact_title)) {
                    val edgeSummary = edge?.id?.let { id -> viewModel.summaries.firstOrNull { it.edge.id == id } }
                    val samples = edgeSummary?.sampleCount ?: 0
                    Text(
                        text = stringResource(R.string.leg_edit_impact_edge, samples),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    edge?.let { e ->
                        val fromCount = e.fromKey?.let { key -> viewModel.summaries.count { it.edge.fromKey == key || it.edge.toKey == key } } ?: 0
                        val toCount = e.toKey?.let { key -> viewModel.summaries.count { it.edge.fromKey == key || it.edge.toKey == key } } ?: 0
                        if (fromCount > 0) {
                            Text(
                                text = stringResource(R.string.leg_edit_impact_point, stringResource(R.string.edge_from), fromCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (toCount > 0) {
                            Text(
                                text = stringResource(R.string.leg_edit_impact_point, stringResource(R.string.edge_to), toCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.edge_name)) {
                    val current = edge
                    Text(
                        text = current?.userName ?: stringResource(R.string.edge_name_add),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (current?.userName != null) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = stringResource(R.string.edge_name_clear_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { renaming = true }, enabled = current != null) {
                        Text(stringResource(if (current?.userName == null) R.string.edge_name_add else R.string.action_edit))
                    }
                }
            }

            item {
                SectionCard(title = stringResource(R.string.edge_bind_road)) {
                    val current = edge
                    val roadNames = current?.roadIds.orEmpty().mapNotNull { id -> viewModel.roads.firstOrNull { it.id == id }?.name }
                    Text(
                        text = if (roadNames.isEmpty()) stringResource(R.string.edge_bind_road_none) else roadNames.joinToString("、"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { bindingRoad = true }, enabled = current != null) {
                        Text(stringResource(R.string.action_edit))
                    }
                }
            }

            val fromPoint = edge?.fromPoint
            val toPoint = edge?.toPoint
            if (fromPoint != null && toPoint != null) {
                item {
                    SectionCard(title = stringResource(R.string.leg_edit_endpoints_title)) {
                        val edgeRoadNames = edge?.roadIds.orEmpty().mapNotNull { id -> viewModel.roads.firstOrNull { it.id == id }?.name }
                        EndpointEditRow(
                            label = stringResource(R.string.edge_from),
                            point = fromPoint,
                            name = edge?.fromName,
                            viewModel = viewModel,
                            edgeRoadNames = edgeRoadNames,
                            onName = {
                                previewAt = edge?.from
                                previewReach = PlaceDrafts.SPAN_M
                                val draft = viewModel.placeDraft(fromPoint, previewAt, previewReach)
                                preview = draft
                                namingPlace = draft
                            },
                            onOpenNetwork = { onOpenNode(fromPoint.key) },
                        )
                        ThinDivider()
                        EndpointEditRow(
                            label = stringResource(R.string.edge_to),
                            point = toPoint,
                            name = edge?.toName,
                            viewModel = viewModel,
                            edgeRoadNames = edgeRoadNames,
                            onName = {
                                previewAt = edge?.to
                                previewReach = PlaceDrafts.SPAN_M
                                val draft = viewModel.placeDraft(toPoint, previewAt, previewReach)
                                preview = draft
                                namingPlace = draft
                            },
                            onOpenNetwork = { onOpenNode(toPoint.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointEditRow(
    label: String,
    point: PointRef,
    name: String?,
    viewModel: AppViewModel,
    edgeRoadNames: List<String>,
    onName: () -> Unit,
    onOpenNetwork: () -> Unit,
) {
    val refs = listOf(point)
    val roads = viewModel.roadsAt(refs)
    val place = viewModel.placeOf(point)
    val sharedEdges = viewModel.summaries.count { it.edge.fromKey == point.key || it.edge.toKey == point.key }
    val inferred = edgeRoadNames.filter { it !in roads }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name ?: stringResource(R.string.point_unnamed), style = MaterialTheme.typography.bodyLarge)
                if (place != null) {
                    Text(
                        text = stringResource(R.string.place_member_count, place.points.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.leg_edit_impact_point_shared, sharedEdges),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (roads.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.point_roads_selected, roads.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (inferred.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.point_infer_roads_from_edge, inferred.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onName) { Text(stringResource(if (name == null) R.string.edge_name_add else R.string.action_edit)) }
                TextButton(onClick = onOpenNetwork) { Text(stringResource(R.string.edge_open_in_network)) }
            }
        }
    }
}

@Composable
private fun EdgeRoadsDialog(
    roads: List<com.geno1024.ai.mapoftime.data.Road>,
    selected: Set<Long>,
    onDismiss: () -> Unit,
    onSave: (Set<Long>) -> Unit,
) {
    var current by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edge_bind_road_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (roads.isEmpty()) {
                    Text(stringResource(R.string.edge_bind_road_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    roads.forEach { road ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = road.id in current,
                                onCheckedChange = { checked ->
                                    current = if (checked) current + road.id else current - road.id
                                },
                            )
                            Text(road.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EdgeNameDialog(
    initial: String,
    reuse: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edge_name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.edge_name)) },
                    placeholder = { Text(stringResource(R.string.edge_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RoadNameReuse(reuse, name) { name = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
