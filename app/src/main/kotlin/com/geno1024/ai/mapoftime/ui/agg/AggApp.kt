package com.geno1024.ai.mapoftime.ui.agg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.agg.AggEdge
import com.geno1024.ai.mapoftime.agg.AggNetwork
import com.geno1024.ai.mapoftime.agg.AggPoint
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.AppMode
import com.geno1024.ai.mapoftime.ui.AppViewModel
import com.geno1024.ai.mapoftime.ui.ModeFilter
import com.geno1024.ai.mapoftime.ui.SectionCard
import com.geno1024.ai.mapoftime.ui.StatRow
import com.geno1024.ai.mapoftime.ui.formatDateTime
import com.geno1024.ai.mapoftime.ui.formatDistance
import com.geno1024.ai.mapoftime.ui.formatDuration
import com.geno1024.ai.mapoftime.ui.formatSeconds
import com.geno1024.ai.mapoftime.ui.map.MapMarker

private enum class AggTab(val labelRes: Int, val glyph: String) {
    NETWORK(R.string.agg_tab_network, "⌁"),
    PLACES(R.string.agg_tab_places, "◇"),
    TRIPS(R.string.agg_tab_trips, "≡"),
}

/** Undirected edge identity, including the mode that keeps timings separate. */
private fun edgeRef(from: Int, to: Int, mode: TripMode) =
    Triple(minOf(from, to), maxOf(from, to), mode)

/**
 * A second, read-only shell over the same raw trips.
 *
 * There is no editing here: points and crossings are recomputed from GPS fixes
 * in [AppViewModel], and the detail screens only read what that run observed.
 * Tapping a line or card opens a dated history with the actual recorded path.
 */
@Composable
fun AggApp(viewModel: AppViewModel) {
    val net = viewModel.aggNetwork
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AggTab.NETWORK.name) }
    var modeFilter by rememberSaveable { mutableStateOf<TripMode?>(null) }
    var selectedEdge by remember { mutableStateOf<Int?>(null) }
    // The road network is the default view; straight links stay available as
    // a quieter overview of the same crossings.
    var showRoads by rememberSaveable { mutableStateOf(true) }
    // A string route keeps the detail stack across rotation without adding a
    // Parcelable type to a shell that is otherwise entirely derived.
    var detailRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = remember(detailRoute) { AggDetail.from(detailRoute) }
    val back = { detailRoute = null }
    BackHandler(enabled = detail != null, onBack = back)

    val edges = remember(net, modeFilter) {
        net.edges.filter { modeFilter == null || it.mode == modeFilter }
    }
    val byId = remember(net) { net.points.associateBy { it.id } }
    val passes = remember(net) { net.edges.flatMap { it.passes } }
    val tripsById = remember(viewModel.trips) { viewModel.trips.associateBy { it.id } }

    Surface(color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (detail != null) return@Scaffold
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    for (item in AggTab.entries) {
                        NavigationBarItem(
                            selected = tab == item.name,
                            onClick = { tab = item.name },
                            icon = { Text(item.glyph) },
                            label = { Text(stringResource(item.labelRes)) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (detail) {
                    null -> Unit
                    is AggDetail.Edge -> {
                        val edge = edges.getOrNull(detail.index)
                        if (edge != null) {
                            AggEdgeDetail(
                                edge = edge,
                                byId = byId,
                                settings = settings,
                                tripsById = tripsById,
                                onBack = back,
                                onOpenTrip = { id -> detailRoute = AggDetail.Trip(id).route() },
                            )
                        } else {
                            AggMissingDetail(back)
                        }
                    }
                    is AggDetail.Point -> {
                        val point = byId[detail.id]
                        if (point != null) {
                            AggPointDetail(
                                point = point,
                                edges = edges,
                                passes = passes,
                                byId = byId,
                                settings = settings,
                                tripsById = tripsById,
                                onBack = back,
                                onOpenEdge = { edge ->
                                    val index = edges.indexOf(edge)
                                    if (index >= 0) detailRoute = AggDetail.Edge(index).route()
                                },
                                onOpenTrip = { id -> detailRoute = AggDetail.Trip(id).route() },
                            )
                        } else {
                            AggMissingDetail(back)
                        }
                    }
                    is AggDetail.Trip -> {
                        val trip = tripsById[detail.id]
                        if (trip != null) {
                            AggTripDetail(
                                viewModel = viewModel,
                                trip = trip,
                                passes = passes.filter { it.tripId == detail.id },
                                byId = byId,
                                settings = settings,
                                onBack = back,
                                onOpenEdge = { edge ->
                                    val index = edges.indexOf(edge)
                                    if (index >= 0) detailRoute = AggDetail.Edge(index).route()
                                },
                            )
                        } else {
                            AggMissingDetail(back)
                        }
                    }
                }

                if (detail == null) {
                    // Header instead of the classic ScreenHeader: a status band
                    // that says where these points came from, not a page title.
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.agg_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            // The settings tab lives in the classic shell; give
                            // this shell its own way back without duplicating it.
                            TextButton(onClick = {
                                viewModel.settings.update { it.copy(appMode = AppMode.CLASSIC) }
                            }) {
                                Text(stringResource(R.string.agg_back_classic))
                            }
                        }
                        Text(
                            text = stringResource(R.string.agg_subtitle, net.trips, net.fixes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        StatRow(
                            stringResource(R.string.agg_stat_points) to net.points.size.toString(),
                            stringResource(R.string.agg_stat_crossings) to net.edges.size.toString(),
                        )
                        Spacer(Modifier.height(6.dp))
                        StatRow(
                            stringResource(R.string.agg_stat_trips) to net.trips.toString(),
                            stringResource(R.string.agg_stat_fixes) to net.fixes.toString(),
                        )
                    }

                    when (AggTab.valueOf(tab)) {
                        AggTab.NETWORK -> {
                            ModeFilter(
                                selected = modeFilter,
                                onSelect = {
                                    modeFilter = it
                                    selectedEdge = null
                                    detailRoute = null
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            FlowRow(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = showRoads,
                                    onClick = { showRoads = true },
                                    label = { Text(stringResource(R.string.agg_style_roads)) },
                                )
                                FilterChip(
                                    selected = !showRoads,
                                    onClick = { showRoads = false },
                                    label = { Text(stringResource(R.string.agg_style_lines)) },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            val edgeIndexes = remember(edges) {
                                edges.mapIndexed { index, edge ->
                                    edgeRef(edge.fromId, edge.toId, edge.mode) to index
                                }.toMap()
                            }
                            val roadEntries = remember(passes, edgeIndexes) {
                                passes.sortedByDescending { it.startedAt }
                                    .mapNotNull { pass ->
                                        val index = edgeIndexes[
                                            edgeRef(pass.fromId, pass.toId, pass.mode)
                                        ] ?: return@mapNotNull null
                                        pass.path to index
                                    }
                                    .take(600)
                            }
                            val networkTracks = remember(showRoads, edges, byId, roadEntries) {
                                if (showRoads) {
                                    roadEntries.map { it.first }
                                } else {
                                    edges.mapNotNull { edge ->
                                        val from = byId[edge.fromId]?.at ?: return@mapNotNull null
                                        val to = byId[edge.toId]?.at ?: return@mapNotNull null
                                        listOf(from, to)
                                    }
                                }
                            }
                            val networkMarkers = remember(net.points) {
                                net.points.sortedByDescending { it.visits }.take(60).mapIndexed { index, point ->
                                    MapMarker(point.at, label = "P${point.id}", emphasized = index < 6)
                                }
                            }
                            val networkBounds = remember(net.points) {
                                Geo.bounds(net.points.map { it.at })?.expanded(60.0)
                            }
                            val networkHighlight = selectedEdge?.let { index ->
                                edges.getOrNull(index)?.let { edge ->
                                    val straight = listOfNotNull(byId[edge.fromId]?.at, byId[edge.toId]?.at)
                                    if (showRoads) {
                                        passes.firstOrNull { pass ->
                                            edgeRef(pass.fromId, pass.toId, pass.mode) ==
                                                edgeRef(edge.fromId, edge.toId, edge.mode)
                                        }?.path ?: straight
                                    } else {
                                        straight
                                    }
                                }
                            }
                            AggDetailMap(
                                settings = settings,
                                tracks = networkTracks,
                                markers = networkMarkers,
                                emptyHint = stringResource(R.string.agg_map_empty),
                                highlight = networkHighlight,
                                focusBounds = if (networkHighlight != null) null else networkBounds,
                                onTrackTap = { index ->
                                    val edgeIndex = if (showRoads) {
                                        roadEntries.getOrNull(index)?.second
                                    } else {
                                        index
                                    }
                                    if (edgeIndex != null) {
                                        selectedEdge = edgeIndex
                                        detailRoute = AggDetail.Edge(edgeIndex).route()
                                    }
                                },
                            )
                            Text(
                                text = stringResource(
                                    if (showRoads) R.string.agg_roads_hint else R.string.agg_lines_hint,
                                    networkTracks.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                            selectedEdge?.let { index ->
                                edges.getOrNull(index)?.let { edge ->
                                    AggEdgeCard(
                                        edge,
                                        byId,
                                        Modifier
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                            .clickable { detailRoute = AggDetail.Edge(index).route() },
                                    )
                                }
                            }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(edges.take(40)) { index, edge ->
                                    AggEdgeCard(
                                        edge,
                                        byId,
                                        Modifier.clickable {
                                            selectedEdge = index
                                            detailRoute = AggDetail.Edge(index).route()
                                        },
                                    )
                                }
                                item { Spacer(Modifier.height(16.dp)) }
                            }
                        }

                        AggTab.PLACES -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    Text(
                                        text = stringResource(R.string.agg_places_hint),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                items(net.points.sortedByDescending { it.visits }, key = { it.id }) { point ->
                                    val lastVisit = remember(point.id, passes) {
                                        passes.filter { it.fromId == point.id || it.toId == point.id }
                                            .maxOfOrNull { it.startedAt }
                                    }
                                    SectionCard(
                                        modifier = Modifier.clickable {
                                            detailRoute = AggDetail.Point(point.id).route()
                                        },
                                        title = stringResource(R.string.agg_point_title, point.id),
                                        subtitle = "%.5f, %.5f".format(point.at.lat, point.at.lon),
                                    ) {
                                        StatRow(
                                            stringResource(R.string.agg_stat_fixes) to point.visits.toString(),
                                            stringResource(R.string.agg_stat_trips) to point.trips.toString(),
                                        )
                                        Text(
                                            text = stringResource(R.string.agg_point_radius, formatDistance(point.radiusM)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (lastVisit != null) {
                                            Text(
                                                text = stringResource(R.string.agg_last_seen, formatDateTime(lastVisit)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Text(
                                            text = stringResource(R.string.agg_action_open),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }

                        AggTab.TRIPS -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(viewModel.trips, key = { it.id }) { trip ->
                                    SectionCard(
                                        modifier = Modifier.clickable {
                                            detailRoute = AggDetail.Trip(trip.id).route()
                                        },
                                        title = stringResource(R.string.agg_trip_title, trip.mode.name),
                                        subtitle = formatDateTime(trip.startedAt),
                                    ) {
                                        StatRow(
                                            stringResource(R.string.agg_stat_fixes) to trip.pointCount.toString(),
                                            stringResource(R.string.agg_trip_distance) to formatDistance(trip.distanceM),
                                            stringResource(R.string.agg_trip_duration) to formatDuration(trip.durationMs),
                                        )
                                        Text(
                                            text = stringResource(R.string.agg_action_open),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AggEdgeCard(
    edge: AggEdge,
    byId: Map<Int, AggPoint>,
    modifier: Modifier = Modifier,
) {
    val lastSeen = edge.passes.maxOfOrNull { it.startedAt }
    SectionCard(
        modifier = modifier,
        title = "P${edge.fromId} ↔ P${edge.toId}",
        subtitle = stringResource(R.string.agg_edge_subtitle, edge.mode.name, edge.samples),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatSeconds(edge.typicalMs),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatDistance(edge.distanceM),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lastSeen != null) {
            Text(
                text = stringResource(R.string.agg_last_seen, formatDateTime(lastSeen)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.agg_action_open),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
