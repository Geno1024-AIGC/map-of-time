package com.geno1024.ai.mapoftime.ui.agg

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.agg.AggEdge
import com.geno1024.ai.mapoftime.agg.AggNetwork
import com.geno1024.ai.mapoftime.agg.AggPoint
import com.geno1024.ai.mapoftime.core.Basemap
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.AppMode
import com.geno1024.ai.mapoftime.ui.AppViewModel
import com.geno1024.ai.mapoftime.ui.ModeFilter
import com.geno1024.ai.mapoftime.ui.SectionCard
import com.geno1024.ai.mapoftime.ui.StatRow
import com.geno1024.ai.mapoftime.ui.formatDistance
import com.geno1024.ai.mapoftime.ui.formatDuration
import com.geno1024.ai.mapoftime.ui.formatSeconds
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.TrackMap

private enum class AggTab(val labelRes: Int, val glyph: String) {
    NETWORK(R.string.agg_tab_network, "⌁"),
    PLACES(R.string.agg_tab_places, "◇"),
    TRIPS(R.string.agg_tab_trips, "≡"),
}

/**
 * A second, read-only shell over the same raw trips.
 *
 * There is no detail stack, no editing and no classic edge/leg state here: the
 * layout is intentionally a different application. Points and crossings are
 * recomputed from GPS fixes in [AppViewModel], so the classic database is
 * never rewritten and both modes can coexist on one installation.
 */
@Composable
fun AggApp(viewModel: AppViewModel) {
    val net = viewModel.aggNetwork
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AggTab.NETWORK.name) }
    var modeFilter by rememberSaveable { mutableStateOf<TripMode?>(null) }
    var selectedEdge by remember { mutableStateOf<Int?>(null) }

    val edges = remember(net, modeFilter) {
        net.edges.filter { modeFilter == null || it.mode == modeFilter }
    }
    val byId = remember(net) { net.points.associateBy { it.id } }

    Surface(color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
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
                // Header instead of the classic ScreenHeader: a status band
                // that says where these points came from, not a page title.
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        AggMap(net, edges, byId, settings.mapTiles, settings.basemap,
                            settings.customTileUrl, settings.customTilesGcj02,
                            selectedEdge) { selectedEdge = it }
                        selectedEdge?.let { index ->
                            edges.getOrNull(index)?.let { edge ->
                                AggEdgeCard(edge, byId, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(edges.take(40), key = { "${it.fromId}-${it.toId}-${it.mode.id}" }) { edge ->
                                AggEdgeCard(edge, byId)
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
                                SectionCard(
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
                                    title = stringResource(R.string.agg_trip_title, trip.mode.name),
                                    subtitle = formatDuration(trip.durationMs),
                                ) {
                                    StatRow(
                                        stringResource(R.string.agg_stat_fixes) to trip.pointCount.toString(),
                                        stringResource(R.string.agg_trip_distance) to formatDistance(trip.distanceM),
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

@Composable
private fun AggMap(
    net: AggNetwork,
    edges: List<AggEdge>,
    byId: Map<Int, AggPoint>,
    tilesEnabled: Boolean,
    basemap: Basemap,
    customTileUrl: String,
    customTilesGcj02: Boolean,
    selectedEdge: Int?,
    onSelectEdge: (Int) -> Unit,
) {
    val tracks = remember(edges, byId) {
        edges.mapNotNull { edge ->
            val from = byId[edge.fromId]?.at ?: return@mapNotNull null
            val to = byId[edge.toId]?.at ?: return@mapNotNull null
            listOf(from, to)
        }
    }
    val markers = remember(net.points, byId) {
        net.points.sortedByDescending { it.visits }.take(60).mapIndexed { index, point ->
            MapMarker(point.at, label = "P${point.id}", emphasized = index < 6)
        }
    }
    val bounds = remember(net.points) {
        Geo.bounds(net.points.map { it.at })?.expanded(60.0)
    }
    val highlight: List<LatLon>? = selectedEdge?.let { index ->
        edges.getOrNull(index)?.let { edge ->
            listOfNotNull(byId[edge.fromId]?.at, byId[edge.toId]?.at)
        }
    }
    TrackMap(
        tracks = tracks,
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        markers = markers,
        tilesEnabled = tilesEnabled,
        basemap = basemap,
        customTileUrl = customTileUrl,
        customTilesGcj02 = customTilesGcj02,
        emptyHint = stringResource(R.string.agg_map_empty),
        onTrackTap = onSelectEdge,
        highlight = highlight,
        focusBounds = if (highlight != null) null else bounds,
    )
}

@Composable
private fun AggEdgeCard(
    edge: AggEdge,
    byId: Map<Int, AggPoint>,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        modifier = modifier,
        title = "P${edge.fromId} → P${edge.toId}",
        subtitle = stringResource(R.string.agg_edge_subtitle, edge.mode.name, edge.samples),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
    }
}
