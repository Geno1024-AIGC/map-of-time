package com.geno1024.ai.mapoftime.ui.agg

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.agg.AggEdge
import com.geno1024.ai.mapoftime.agg.AggPass
import com.geno1024.ai.mapoftime.agg.AggPoint
import com.geno1024.ai.mapoftime.core.Bounds
import com.geno1024.ai.mapoftime.core.Geo
import com.geno1024.ai.mapoftime.core.LatLon
import com.geno1024.ai.mapoftime.core.TrackPoint
import com.geno1024.ai.mapoftime.data.SettingsSnapshot
import com.geno1024.ai.mapoftime.data.Trip
import com.geno1024.ai.mapoftime.ui.AppViewModel
import com.geno1024.ai.mapoftime.ui.ScreenHeader
import com.geno1024.ai.mapoftime.ui.SectionCard
import com.geno1024.ai.mapoftime.ui.StatRow
import com.geno1024.ai.mapoftime.ui.formatDateTime
import com.geno1024.ai.mapoftime.ui.formatDistance
import com.geno1024.ai.mapoftime.ui.formatDuration
import com.geno1024.ai.mapoftime.ui.formatSeconds
import com.geno1024.ai.mapoftime.ui.map.MapMarker
import com.geno1024.ai.mapoftime.ui.map.TrackMap

/**
 * Routes are stored as strings so the whole detail stack survives rotation
 * without inventing a Parcelable for three tiny selections. An edge is an
 * index into the current (mode-filtered) list; ids are stable within one
 * aggregation run.
 */
internal sealed interface AggDetail {
    data class Edge(val index: Int) : AggDetail {
        fun route(): String = "e:$index"
    }

    data class Point(val id: Int) : AggDetail {
        fun route(): String = "p:$id"
    }

    data class Trip(val id: Long) : AggDetail {
        fun route(): String = "t:$id"
    }

    companion object {
        fun from(route: String?): AggDetail? {
            val parts = route?.split(":") ?: return null
            return when (parts.getOrNull(0)) {
                "e" -> parts.getOrNull(1)?.toIntOrNull()?.let(::Edge)
                "p" -> parts.getOrNull(1)?.toIntOrNull()?.let(::Point)
                "t" -> parts.getOrNull(1)?.toLongOrNull()?.let(::Trip)
                else -> null
            }
        }
    }
}

/** One map configuration for the network and every detail screen. */
@Composable
internal fun AggDetailMap(
    settings: SettingsSnapshot,
    tracks: List<List<LatLon>>,
    markers: List<MapMarker>,
    emptyHint: String,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(280.dp),
    highlight: List<LatLon>? = null,
    focusBounds: Bounds? = null,
    onTrackTap: ((Int) -> Unit)? = null,
) {
    TrackMap(
        tracks = tracks,
        modifier = modifier,
        markers = markers,
        tilesEnabled = settings.mapTiles,
        basemap = settings.basemap,
        customTileUrl = settings.customTileUrl,
        customTilesGcj02 = settings.customTilesGcj02,
        emptyHint = emptyHint,
        highlight = highlight,
        focusBounds = focusBounds,
        onTrackTap = onTrackTap,
    )
}

/**
 * Why one connection has the typical time it has: every real crossing is a
 * dated row, and tapping one draws the GPS fixes the user actually recorded.
 */
@Composable
internal fun AggEdgeDetail(
    edge: AggEdge,
    byId: Map<Int, AggPoint>,
    settings: SettingsSnapshot,
    tripsById: Map<Long, Trip>,
    onBack: () -> Unit,
    onOpenTrip: (Long) -> Unit,
) {
    val passes = edge.passes
    var selectedIndex by remember(edge) { mutableStateOf(0) }
    val selected = passes.getOrNull(selectedIndex)
    val endpoints = remember(edge, byId) {
        listOfNotNull(byId[edge.fromId]?.at, byId[edge.toId]?.at)
    }
    val markers = remember(edge, byId) {
        listOfNotNull(
            byId[edge.fromId]?.let { MapMarker(it.at, label = "P${it.id}", emphasized = true) },
            byId[edge.toId]?.let { MapMarker(it.at, label = "P${it.id}", emphasized = true) },
        )
    }
    // A hundred overlapping paths stop being information. The list below is
    // still complete; the map shows the most recent ones.
    val tracks = remember(passes) { passes.take(120).map { it.path } }
    val focus = remember(endpoints) { Geo.bounds(endpoints)?.expanded(80.0) }
    val durations = remember(passes) { passes.map { it.durationMs } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.agg_edge_title, edge.fromId, edge.toId),
            subtitle = stringResource(R.string.agg_edge_subtitle, edge.mode.name, passes.size),
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            StatRow(
                stringResource(R.string.agg_detail_typical) to formatSeconds(edge.typicalMs),
                stringResource(R.string.agg_trip_distance) to formatDistance(edge.distanceM),
                stringResource(R.string.agg_edge_fastest) to formatSeconds(durations.minOrNull() ?: 0L),
                stringResource(R.string.agg_edge_slowest) to formatSeconds(durations.maxOrNull() ?: 0L),
            )
            Spacer(Modifier.height(10.dp))
            AggDetailMap(
                settings = settings,
                tracks = tracks,
                markers = markers,
                emptyHint = stringResource(R.string.agg_detail_map_empty),
                highlight = selected?.path,
                focusBounds = focus,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.agg_detail_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(passes) { index, pass ->
                SectionCard(
                    modifier = Modifier.clickable { selectedIndex = index },
                    title = stringResource(R.string.agg_edge_pass, passes.size - index),
                    subtitle = formatDateTime(pass.startedAt),
                ) {
                    Row {
                        Text(
                            text = formatSeconds(pass.durationMs),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.agg_edge_direction, pass.fromId, pass.toId),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = stringResource(R.string.agg_detail_fix_trip, pass.fixes, pass.tripId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    tripsById[pass.tripId]?.note?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onOpenTrip(pass.tripId) }) {
                        Text(stringResource(R.string.agg_trip_open))
                    }
                }
            }
        }
    }
}

/** A place is only interesting together with where it connects and when it was visited. */
@Composable
internal fun AggPointDetail(
    point: AggPoint,
    edges: List<AggEdge>,
    passes: List<AggPass>,
    byId: Map<Int, AggPoint>,
    settings: SettingsSnapshot,
    tripsById: Map<Long, Trip>,
    onBack: () -> Unit,
    onOpenEdge: (AggEdge) -> Unit,
    onOpenTrip: (Long) -> Unit,
) {
    val connections = remember(edges, point.id) {
        edges.filter { it.fromId == point.id || it.toId == point.id }
            .sortedByDescending { it.samples }
    }
    val history = remember(passes, point.id) {
        passes.filter { it.fromId == point.id || it.toId == point.id }
    }
    val markers = remember(point, connections, byId) {
        buildList {
            add(MapMarker(point.at, label = "P${point.id}", emphasized = true))
            connections.map { if (it.fromId == point.id) it.toId else it.fromId }
                .distinct()
                .take(24)
                .forEach { id ->
                    byId[id]?.let { add(MapMarker(it.at, label = "P$id")) }
                }
        }
    }
    val tracks = remember(history) { history.take(120).map { it.path } }
    val focus = remember(point) {
        Geo.bounds(listOf(point.at))?.expanded(maxOf(point.radiusM, 40.0) + 80.0)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.agg_point_title, point.id),
            subtitle = "%.5f, %.5f".format(point.at.lat, point.at.lon),
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            StatRow(
                stringResource(R.string.agg_stat_fixes) to point.visits.toString(),
                stringResource(R.string.agg_stat_trips) to point.trips.toString(),
                stringResource(R.string.agg_point_connections) to connections.size.toString(),
            )
            Spacer(Modifier.height(10.dp))
            AggDetailMap(
                settings = settings,
                tracks = tracks,
                markers = markers,
                emptyHint = stringResource(R.string.agg_detail_map_empty),
                focusBounds = focus,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.agg_point_connections),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(connections, key = { "${it.fromId}-${it.toId}-${it.mode.id}" }) { edge ->
                val other = if (edge.fromId == point.id) edge.toId else edge.fromId
                SectionCard(
                    modifier = Modifier.clickable { onOpenEdge(edge) },
                    title = stringResource(R.string.agg_point_connection, other, edge.mode.name),
                    subtitle = stringResource(R.string.agg_edge_subtitle, edge.mode.name, edge.samples),
                ) {
                    Text(
                        text = formatSeconds(edge.typicalMs),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.agg_point_history),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(history.take(100)) { pass ->
                SectionCard(
                    title = formatDateTime(pass.startedAt),
                    subtitle = stringResource(
                        R.string.agg_point_visit,
                        pass.fromId,
                        pass.toId,
                        formatSeconds(pass.durationMs),
                    ),
                ) {
                    TextButton(onClick = { onOpenTrip(pass.tripId) }) {
                        Text(stringResource(R.string.agg_trip_open))
                    }
                    tripsById[pass.tripId]?.note?.let { note ->
                        Text(text = note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** The unmodified trip behind the aggregation, plus the crossings it produced. */
@Composable
internal fun AggTripDetail(
    viewModel: AppViewModel,
    trip: Trip,
    passes: List<AggPass>,
    byId: Map<Int, AggPoint>,
    settings: SettingsSnapshot,
    onBack: () -> Unit,
    onOpenEdge: (AggEdge) -> Unit,
) {
    var rawPoints by remember(trip.id) { mutableStateOf<List<TrackPoint>>(emptyList()) }
    LaunchedEffect(trip.id) { rawPoints = viewModel.points(trip.id) }

    val tracks = remember(rawPoints, passes) {
        if (rawPoints.size >= 2) {
            listOf(rawPoints.map { it.at })
        } else {
            passes.sortedBy { it.startedAt }.map { it.path }
        }
    }
    val markers = remember(passes, byId) {
        passes.flatMap { listOf(it.fromId, it.toId) }
            .distinct()
            .take(40)
            .mapNotNull { id -> byId[id]?.let { MapMarker(it.at, label = "P$id", emphasized = true) } }
    }
    val focus = remember(rawPoints, passes) {
        Geo.bounds(rawPoints.map { it.at })?.expanded(60.0)
            ?: Geo.bounds(passes.flatMap { it.path })?.expanded(60.0)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.agg_trip_title, trip.mode.name),
            subtitle = formatDateTime(trip.startedAt),
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            StatRow(
                stringResource(R.string.agg_trip_duration) to formatDuration(trip.durationMs),
                stringResource(R.string.agg_trip_distance) to formatDistance(trip.distanceM),
                stringResource(R.string.agg_stat_fixes) to trip.pointCount.toString(),
                stringResource(R.string.agg_stat_crossings) to passes.size.toString(),
            )
            trip.note?.let { note ->
                Text(
                    text = stringResource(R.string.agg_trip_note, note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            AggDetailMap(
                settings = settings,
                tracks = tracks,
                markers = markers,
                emptyHint = stringResource(R.string.agg_detail_map_empty),
                focusBounds = focus,
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.agg_trip_crossings),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(passes.sortedBy { it.startedAt }) { pass ->
                SectionCard(
                    title = stringResource(R.string.agg_edge_direction, pass.fromId, pass.toId),
                    subtitle = formatDateTime(pass.startedAt),
                ) {
                    Row {
                        Text(
                            text = formatSeconds(pass.durationMs),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.agg_detail_fix_only, pass.fixes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AggMissingDetail(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.agg_detail_missing_title),
            onBack = onBack,
        )
        Text(
            text = stringResource(R.string.agg_detail_missing),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
