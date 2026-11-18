package com.geno1024.ai.mapoftime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.geno1024.ai.mapoftime.R

private enum class Tab(val labelRes: Int, val glyph: String) {
    RECORD(R.string.nav_record, "◉"),
    TRIPS(R.string.nav_trips, "≡"),
    SEGMENTS(R.string.nav_segments, "⌁"),
    SETTINGS(R.string.nav_settings, "⚙"),
}

/**
 * Top-level navigation: four tabs plus a one-level detail stack.
 *
 * A detail route is stored as a string (`trip:7`, `segment:12`) so it survives
 * a rotation without needing a custom `Saver` for a sealed hierarchy.
 */
@Composable
fun App(viewModel: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.RECORD.name) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }

    val closeDetail = { detail = null }
    BackHandler(enabled = detail != null) { detail = null }

    Surface(color = MaterialTheme.colorScheme.background) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { entry ->
                        val selected = tab == entry.name && detail == null
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                detail = null
                                tab = entry.name
                            },
                            icon = { Text(entry.glyph) },
                            label = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
            },
        ) { padding ->
            val route = detail
            val tripId = route?.takeIf { it.startsWith("trip:") }
                ?.removePrefix("trip:")?.toLongOrNull()
            val segmentId = route?.takeIf { it.startsWith("segment:") }
                ?.removePrefix("segment:")?.toLongOrNull()
            when {
                tripId != null -> TripDetailScreen(
                    viewModel = viewModel,
                    tripId = tripId,
                    onBack = closeDetail,
                    modifier = Modifier.padding(padding),
                )

                segmentId != null -> SegmentDetailScreen(
                    viewModel = viewModel,
                    segmentId = segmentId,
                    onBack = closeDetail,
                    modifier = Modifier.padding(padding),
                )

                else -> when (Tab.entries.firstOrNull { it.name == tab } ?: Tab.RECORD) {
                    Tab.RECORD -> RecordScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(padding),
                    )

                    Tab.TRIPS -> TripsScreen(
                        viewModel = viewModel,
                        onOpenTrip = { id -> detail = "trip:$id" },
                        modifier = Modifier.padding(padding),
                    )

                    Tab.SEGMENTS -> SegmentsScreen(
                        viewModel = viewModel,
                        onOpenSegment = { id -> detail = "segment:$id" },
                        modifier = Modifier.padding(padding),
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}
