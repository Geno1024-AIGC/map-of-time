package com.geno1024.ai.mapoftime.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geno1024.ai.mapoftime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.state.collectAsStateWithLifecycle()

    var tileCount by remember { mutableStateOf<Int?>(null) }
    var cacheMessage by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    // Hoisted: reading resources through LocalContext inside a click handler is
    // not configuration-aware and would survive a locale change with stale text.
    val clearedText = stringResource(R.string.settings_cleared)
    val exportErrorText = stringResource(R.string.settings_export_error)
    val exportTitle = stringResource(R.string.settings_export)

    LaunchedEffect(Unit) {
        tileCount = viewModel.cachedTileCount()
    }

    Column(Modifier.fillMaxSize()) {
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
                SettingField(
                    label = stringResource(R.string.settings_tile_url),
                    value = settings.tileUrlTemplate,
                    onCommit = { text ->
                        viewModel.settings.update { it.copy(tileUrlTemplate = text) }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_attribution),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            }

            SectionCard(title = stringResource(R.string.settings_about)) {
                KeyValueRow(
                    label = stringResource(R.string.settings_version),
                    value = rememberVersionName(),
                )
            }
        }
    }
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

@Composable
private fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}
