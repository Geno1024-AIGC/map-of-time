package com.geno1024.ai.mapoftime.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.core.Basemap
import com.geno1024.ai.mapoftime.core.HolidayBlock
import com.geno1024.ai.mapoftime.core.Holidays
import com.geno1024.ai.mapoftime.core.parseHolidayBlock
import com.geno1024.ai.mapoftime.core.parseHolidayDates
import com.geno1024.ai.mapoftime.data.DangerCounts
import com.geno1024.ai.mapoftime.update.Mirrors
import com.geno1024.ai.mapoftime.update.UpdateError
import com.geno1024.ai.mapoftime.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** The four irreversible actions in the Danger Zone, each with its own confirm. */
private enum class DangerAction {
    POINT_NAMES, PLACES, TRIPS, ALL;

    fun title(): Int = when (this) {
        POINT_NAMES -> R.string.settings_danger_point_names
        PLACES -> R.string.settings_danger_places
        TRIPS -> R.string.settings_danger_trips
        ALL -> R.string.settings_danger_all
    }

    fun confirm(): Int = when (this) {
        POINT_NAMES -> R.string.settings_danger_point_names_confirm
        PLACES -> R.string.settings_danger_places_confirm
        TRIPS -> R.string.settings_danger_trips_confirm
        ALL -> R.string.settings_danger_all_confirm
    }

    fun count(counts: DangerCounts): Int = when (this) {
        POINT_NAMES -> counts.pointNames
        PLACES -> counts.places
        TRIPS -> counts.trips
        ALL -> 0
    }
}

@Composable
fun SettingsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()

    var tileCount by remember { mutableStateOf<Int?>(null) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var dangerCounts by remember { mutableStateOf(DangerCounts()) }
    var dangerMessage by remember { mutableStateOf<String?>(null) }
    var confirmDanger by remember { mutableStateOf<DangerAction?>(null) }
    // Hoisted: reading resources through LocalContext inside a click handler is
    // not configuration-aware and would survive a locale change with stale text.
    val clearedText = stringResource(R.string.settings_cleared)
    val exportErrorText = stringResource(R.string.settings_export_error)
    val exportTitle = stringResource(R.string.settings_export)
    val importErrorText = stringResource(R.string.settings_import_error)
    val importDoneFormat = stringResource(R.string.settings_import_done)
    val dangerPointDoneFormat = stringResource(R.string.settings_danger_point_names_done)
    val dangerPlacesDoneFormat = stringResource(R.string.settings_danger_places_done)
    val dangerTripsDoneFormat = stringResource(R.string.settings_danger_trips_done)
    val dangerAllDoneText = stringResource(R.string.settings_danger_all_done)

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importMessage = null
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    // The grant is per-activity by default; persisting it keeps
                    // the read valid even if this coroutine is slow.
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                importMessage = importErrorText
                return@launch
            }
            val result = runCatching { viewModel.importJson(text) }.getOrNull()
            importMessage = if (result == null) {
                importErrorText
            } else {
                String.format(importDoneFormat, result.tripsAdded, result.tripsSkipped)
            }
        }
    }

    LaunchedEffect(Unit) {
        tileCount = viewModel.cachedTileCount()
    }

    // Counts come from the database, not from the cached lists: a dialog must
    // never quote a number that a deletion on another screen has invalidated.
    LaunchedEffect(Unit) {
        dangerCounts = viewModel.dangerCounts()
    }
    val reloadDangerCounts = {
        scope.launch { dangerCounts = viewModel.dangerCounts() }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.settings_title))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_matching)) {
                SwitchRow(
                    title = stringResource(R.string.settings_matching),
                    description = stringResource(R.string.settings_matching_desc),
                    checked = settings.osmMatching,
                    onCheckedChange = { enabled ->
                        viewModel.settings.update { it.copy(osmMatching = enabled) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingField(
                    label = stringResource(R.string.settings_endpoint),
                    value = settings.overpassEndpoint,
                    onCommit = { text ->
                        viewModel.settings.update { it.copy(overpassEndpoint = text) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                val days = remember(settings.osmCacheDays) { settings.osmCacheDays.toFloat() }
                SliderRow(
                    label = stringResource(R.string.settings_cache_days),
                    value = days,
                    range = 0f..365f,
                    format = { picked ->
                        pluralStringResource(R.plurals.settings_cache_days_value, picked.toInt(), picked.toInt())
                    },
                    onCommit = { picked ->
                        viewModel.settings.update { it.copy(osmCacheDays = picked.toInt()) }
                    },
                )
                val snap = remember(settings.junctionSnapMeters) {
                    settings.junctionSnapMeters.toFloat()
                }
                SliderRow(
                    label = stringResource(R.string.settings_snap),
                    value = snap,
                    range = 10f..60f,
                    format = { picked ->
                        stringResource(R.string.settings_snap_value, picked.toInt())
                    },
                    onCommit = { picked ->
                        viewModel.settings.update { it.copy(junctionSnapMeters = picked.toInt()) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = stringResource(R.string.settings_partial),
                    description = stringResource(R.string.settings_partial_desc),
                    checked = settings.includePartial,
                    onCheckedChange = { include ->
                        viewModel.settings.update { it.copy(includePartial = include) }
                        viewModel.refresh()
                    },
                )
            }

            HolidayCard(
                customHolidays = settings.customHolidays,
                customWorkdays = settings.customWorkdays,
                onHolidays = { next ->
                    viewModel.settings.update { it.copy(customHolidays = next) }
                    // The buckets are keyed by calendar: a changed table means
                    // a changed "now" number until the next refresh.
                    viewModel.refresh()
                },
                onWorkdays = { next ->
                    viewModel.settings.update { it.copy(customWorkdays = next) }
                    viewModel.refresh()
                },
            )

            SectionCard(title = stringResource(R.string.settings_tiles)) {
                SwitchRow(
                    title = stringResource(R.string.settings_tiles),
                    description = stringResource(R.string.settings_tiles_desc),
                    checked = settings.mapTiles,
                    onCheckedChange = { enabled ->
                        viewModel.settings.update { it.copy(mapTiles = enabled) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_basemap),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Basemap.entries.forEach { source ->
                        FilterChip(
                            selected = settings.basemap == source,
                            onClick = {
                                viewModel.settings.update { it.copy(basemap = source) }
                            },
                            label = { Text(stringResource(source.labelRes())) },
                        )
                    }
                }
                if (settings.basemap == Basemap.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    SettingField(
                        label = stringResource(R.string.settings_tile_url),
                        value = settings.customTileUrl,
                        onCommit = { text ->
                            viewModel.settings.update { it.copy(customTileUrl = text) }
                        },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_custom_gcj02),
                        description = stringResource(R.string.settings_custom_gcj02_desc),
                        checked = settings.customTilesGcj02,
                        onCheckedChange = { gcj ->
                            viewModel.settings.update { it.copy(customTilesGcj02 = gcj) }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                val attribution = when (settings.basemap) {
                    Basemap.OSM -> stringResource(R.string.settings_attribution)
                    Basemap.AMAP -> stringResource(R.string.settings_attribution_amap)
                    Basemap.CUSTOM -> null
                }
                if (attribution != null) {
                    Text(
                        text = attribution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                Text(
                    text = pluralStringResource(
                        R.plurals.settings_clear_cache_value,
                        tileCount ?: 0,
                        tileCount ?: 0,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        viewModel.clearOsmCache {
                            tileCount = 0
                            cacheMessage = clearedText
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_clear_cache))
                }
                cacheMessage?.let { text ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_export)) {
                Text(
                    text = stringResource(R.string.settings_export_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val json = runCatching { viewModel.exportJson() }.getOrNull()
                            if (json == null) {
                                exportMessage = exportErrorText
                                return@launch
                            }
                            val shared = runCatching {
                                withContext(Dispatchers.IO) {
                                    val dir = File(context.cacheDir, "exports")
                                    dir.mkdirs()
                                    val file = File(dir, "map-of-time-${System.currentTimeMillis()}.json")
                                    file.writeText(json)
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.files",
                                        file,
                                    )
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                }
                            }.getOrNull()
                            if (shared == null) {
                                exportMessage = exportErrorText
                            } else {
                                context.startActivity(
                                    Intent.createChooser(
                                        shared,
                                        exportTitle,
                                    ),
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_export))
                }
                exportMessage?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_import))
                }
                importMessage?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The only card with a network call of its own. The Danger Zone
            // follows it so the destructive actions stay at the very bottom.
            UpdateCard(
                viewModel = viewModel,
                currentVersion = rememberVersionName(),
                mirrorPrefix = settings.updateMirrorPrefix,
                onSelectMirror = { prefix ->
                    viewModel.settings.update { it.copy(updateMirrorPrefix = prefix) }
                },
            )

            SectionCard(
                title = stringResource(R.string.settings_danger_zone),
                subtitle = stringResource(R.string.settings_danger_zone_desc),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.settings_danger_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    DangerButton(
                        text = "${stringResource(DangerAction.POINT_NAMES.title())} (${dangerCounts.pointNames})",
                        onClick = { confirmDanger = DangerAction.POINT_NAMES },
                    )
                    DangerButton(
                        text = "${stringResource(DangerAction.PLACES.title())} (${dangerCounts.places})",
                        onClick = { confirmDanger = DangerAction.PLACES },
                    )
                    DangerButton(
                        text = "${stringResource(DangerAction.TRIPS.title())} (${dangerCounts.trips})",
                        onClick = { confirmDanger = DangerAction.TRIPS },
                    )
                    DangerButton(
                        text = stringResource(DangerAction.ALL.title()),
                        onClick = { confirmDanger = DangerAction.ALL },
                    )
                    dangerMessage?.let { text ->
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        confirmDanger?.let { action ->
            AlertDialog(
                onDismissRequest = { confirmDanger = null },
                title = { Text(stringResource(action.title())) },
                text = {
                    Text(
                        if (action == DangerAction.ALL) {
                            stringResource(action.confirm())
                        } else {
                            stringResource(action.confirm(), action.count(dangerCounts))
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDanger = null
                            when (action) {
                                DangerAction.POINT_NAMES -> viewModel.clearPointNames { removed ->
                                    dangerMessage = String.format(dangerPointDoneFormat, removed)
                                    reloadDangerCounts()
                                }
                                DangerAction.PLACES -> viewModel.clearPlaces { removed ->
                                    dangerMessage = String.format(dangerPlacesDoneFormat, removed)
                                    reloadDangerCounts()
                                }
                                DangerAction.TRIPS -> viewModel.clearTrips { removed ->
                                    dangerMessage = String.format(dangerTripsDoneFormat, removed)
                                    reloadDangerCounts()
                                }
                                DangerAction.ALL -> viewModel.clearAllData {
                                    dangerMessage = dangerAllDoneText
                                    reloadDangerCounts()
                                    tileCount = 0
                                    cacheMessage = clearedText
                                }
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDanger = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }
}

/** One destructive action. Error colours make the bottom card unmistakable. */
@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Text(text)
    }
}

/**
 * The whole update flow in one card: what is installed, what is published, and
 * the single next action. Every state maps to exactly one button, so the user
 * never has to guess whether to check, download or install.
 */
@Composable
private fun UpdateCard(
    viewModel: AppViewModel,
    currentVersion: String,
    mirrorPrefix: String,
    onSelectMirror: (String) -> Unit,
) {
    val state = viewModel.update
    SectionCard(title = stringResource(R.string.settings_update)) {
        KeyValueRow(
            label = stringResource(R.string.update_current),
            value = currentVersion,
        )
        when (state) {
            UpdateState.Idle -> Unit

            UpdateState.Checking -> {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.update_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            UpdateState.NoRelease -> UpdateHint(stringResource(R.string.update_no_release))

            is UpdateState.UpToDate -> UpdateHint(stringResource(R.string.update_up_to_date))

            is UpdateState.Available -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_available, state.release.version.base),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                state.release.notes.takeIf { it.isNotBlank() }?.let { notes ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.update_notes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.release.publishedAt > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.update_published,
                            formatDateTime(state.release.publishedAt),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { viewModel.downloadUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.update_download))
                }
            }

            is UpdateState.Downloading -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_downloading, state.percent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is UpdateState.Ready -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_ready),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.needsPermission) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.update_needs_permission),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { viewModel.installUpdate() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.update_install))
                }
            }

            is UpdateState.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        when (state.error) {
                            UpdateError.NETWORK -> R.string.update_failed_network
                            UpdateError.RATE_LIMIT -> R.string.update_failed_rate_limit
                            UpdateError.UNREADABLE -> R.string.update_failed_unreadable
                            UpdateError.VERSION -> R.string.update_failed_version
                            UpdateError.DOWNLOAD -> R.string.update_failed_download
                            UpdateError.INSTALL -> R.string.update_failed_install
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // A download owns the card; checking over it would throw the bytes away.
        val busy = state is UpdateState.Downloading
        TextButton(
            onClick = { viewModel.checkForUpdate(manual = true) },
            enabled = !busy && state !is UpdateState.Checking,
        ) {
            Text(stringResource(R.string.update_check))
        }

        // Only the APK transfer is mirrored; release metadata stays on the API.
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.update_source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Mirrors.PRESETS.forEach { mirror ->
                FilterChip(
                    selected = mirror.prefix == mirrorPrefix,
                    onClick = { onSelectMirror(mirror.prefix) },
                    label = { Text(mirror.label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SettingField(
            label = stringResource(R.string.update_source_custom),
            value = mirrorPrefix,
            onCommit = onSelectMirror,
        )
    }
}

@Composable
private fun UpdateHint(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Text field that only writes to preferences once editing ends, so typing a URL
 * does not create one preference write per keystroke.
 */
@Composable
private fun SettingField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (!state.isFocused && text != value && text.isNotBlank()) {
                    onCommit(text.trim())
                }
            },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = ImeAction.Done,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onDone = { focusManager.clearFocus() },
        ),
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: @Composable (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(local),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
        )
    }
}

/**
 * The shipped table is right for the years it was written for and wrong the
 * moment the State Council publishes the next one. Fetching a calendar would
 * let a bad one silently relabel a year of trips, so the exceptions are typed
 * in here instead: days off the table does not know, and weekends spent at work.
 *
 * Additions only ever add. A company that gives two extra days just extends the
 * range, and the two blocks that touch are read as one stretch — one departure,
 * one return.
 */
@Composable
private fun HolidayCard(
    customHolidays: List<HolidayBlock>,
    customWorkdays: Set<LocalDate>,
    onHolidays: (List<HolidayBlock>) -> Unit,
    onWorkdays: (Set<LocalDate>) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var workdayText by remember { mutableStateOf("") }
    var badBlock by remember { mutableStateOf(false) }
    var badWorkday by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val badDate = stringResource(R.string.settings_holidays_bad_date)

    SectionCard(title = stringResource(R.string.settings_holidays)) {
        Text(
            text = stringResource(R.string.settings_holidays_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        KeyValueRow(
            label = stringResource(R.string.settings_holidays_builtin),
            value = "${Holidays.coveredYears.first}–${Holidays.coveredYears.last}",
        )

        if (customHolidays.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_holidays_extra),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (block in customHolidays) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatHolidayBlock(block),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onHolidays(customHolidays - block) }) {
                        Text(stringResource(R.string.settings_holidays_remove))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = first,
                onValueChange = { first = it; badBlock = false },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_holidays_first)) },
            )
            OutlinedTextField(
                value = last,
                onValueChange = { last = it; badBlock = false },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_holidays_last)) },
            )
        }
        if (badBlock) FieldError(badDate)
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                val text = if (last.isBlank()) first.trim() else "${first.trim()}..${last.trim()}"
                val block = parseHolidayBlock(text)
                if (block == null) {
                    badBlock = true
                } else {
                    onHolidays((customHolidays + block).distinct())
                    first = ""
                    last = ""
                    focus.clearFocus()
                }
            },
            enabled = first.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_holidays_add))
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_holidays_workdays),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.settings_holidays_workdays_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (day in customWorkdays.sorted()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatHolidayDay(day),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onWorkdays(customWorkdays - day) }) {
                    Text(stringResource(R.string.settings_holidays_remove))
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = workdayText,
                onValueChange = { workdayText = it; badWorkday = false },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_holidays_workday_label)) },
            )
            Button(
                onClick = {
                    val days = parseHolidayDates(workdayText)
                    if (days == null) {
                        badWorkday = true
                    } else {
                        onWorkdays(customWorkdays + days)
                        workdayText = ""
                        focus.clearFocus()
                    }
                },
                enabled = workdayText.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_holidays_add_short))
            }
        }
        if (badWorkday) FieldError(badDate)

        if (customHolidays.isNotEmpty() || customWorkdays.isNotEmpty()) {
            TextButton(
                onClick = {
                    onHolidays(emptyList())
                    onWorkdays(emptySet())
                },
            ) {
                Text(stringResource(R.string.settings_holidays_clear))
            }
        }
    }
}

@Composable
private fun FieldError(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}

private fun Basemap.labelRes(): Int = when (this) {
    Basemap.OSM -> R.string.basemap_osm
    Basemap.AMAP -> R.string.basemap_amap
    Basemap.CUSTOM -> R.string.basemap_custom
}
