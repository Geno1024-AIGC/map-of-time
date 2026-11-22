package com.geno1024.ai.mapoftime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geno1024.ai.mapoftime.R
import com.geno1024.ai.mapoftime.analysis.PlaceDraft
import com.geno1024.ai.mapoftime.core.Confidence
import com.geno1024.ai.mapoftime.core.TripMode
import com.geno1024.ai.mapoftime.data.MatchKind

/** A rounded block with an optional heading; the only container the screens use. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

/** One number with a caption, laid out in a row of two or three. */
@Composable
fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Two columns of numbers: distance/duration, fixes/legs, and so on. */
@Composable
fun StatRow(vararg cells: Pair<String, String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        cells.forEach { (label, value) ->
            StatCell(label = label, value = value, modifier = Modifier.weight(1f))
        }
    }
}

/** Label on the left, value on the right; used by the detail cards. */
@Composable
fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun MatchBadge(kind: MatchKind, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (kind) {
            MatchKind.OSM -> R.string.match_osm
            MatchKind.GEOMETRIC -> R.string.match_geometric
            MatchKind.PARTIAL -> R.string.match_partial
            MatchKind.UNMATCHED -> R.string.match_unmatched
        },
    )
    val color = when (kind) {
        MatchKind.OSM -> MaterialTheme.colorScheme.primary
        MatchKind.GEOMETRIC -> MaterialTheme.colorScheme.secondary
        MatchKind.PARTIAL -> MaterialTheme.colorScheme.outline
        MatchKind.UNMATCHED -> MaterialTheme.colorScheme.error
    }
    Pill(text = label, color = color, modifier = modifier)
}

@Composable
fun ConfidenceBadge(confidence: Confidence, modifier: Modifier = Modifier) {
    val color = when (confidence) {
        Confidence.HIGH -> MaterialTheme.colorScheme.primary
        Confidence.MEDIUM -> MaterialTheme.colorScheme.secondary
        Confidence.LOW -> MaterialTheme.colorScheme.outline
        Confidence.NONE -> MaterialTheme.colorScheme.error
    }
    Pill(
        text = confidenceLabel(confidence),
        color = color,
        modifier = modifier,
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * Mode selector; `null` means "everything". Wraps because four modes plus
 * "all" do not fit on one line of a phone.
 */
@Composable
fun ModeFilter(
    selected: TripMode?,
    onSelect: (TripMode?) -> Unit,
    modifier: Modifier = Modifier,
    includeAll: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (includeAll) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.filter_all)) },
            )
        }
        TripMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(modeLabel(mode)) },
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        ) {
            Spacer(Modifier.size(48.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Title, optional back button, optional trailing controls. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("‹ " + stringResource(R.string.action_back))
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actions()
    }
}

/** A one-pixel rule that works on the dark surface. */
@Composable
fun ThinDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )
}

/**
 * The review step between tapping "name this junction" and naming it.
 *
 * The circle is on the map; this is the caption: how many points, how wide,
 * and the fact that nothing has been written yet. Worth the extra tap — the
 * one mistake that cannot be fixed by renaming is a junction that swallowed
 * the crossing next door.
 */
@Composable
fun PlacePreviewRow(
    draft: PlaceDraft,
    onName: () -> Unit,
    onCancel: () -> Unit,
    /** Offered while a bigger reach is still available: big junctions are real. */
    onWider: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.place_summary, draft.count, formatDistance(draft.spanM)),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.place_preview_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onWider != null) {
            TextButton(onClick = onWider) {
                Text(stringResource(R.string.place_preview_wider))
            }
        }
        TextButton(onClick = onName) {
            Text(stringResource(R.string.place_preview_name))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/**
 * Road names suggested for a point, chosen by the user before saving.
 *
 * Several roads meeting at one point make a crossing; the app spells that as
 * `road × road` from this list. The chips are inference from nearby segments
 * and points — nothing is written until the surrounding dialog is confirmed.
 */
@Composable
fun PointRoadsPicker(
    candidates: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.point_roads_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val all = (candidates + selected.toList()).distinct()
    if (all.isEmpty()) {
        Text(
            text = stringResource(R.string.point_roads_none),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            all.forEach { road ->
                FilterChip(
                    selected = road in selected,
                    onClick = { onToggle(road) },
                    label = { Text(road) },
                )
            }
        }
    }
}

/**
 * Asks for a name for a junction, and says how many points it will cover.
 *
 * The point count is the whole reason this dialog exists: a wide crossing is a
 * dozen corners and lane ends, and they keep growing as new turns are driven.
 * Naming them one by one never converges, so the default action names them all
 * and [onSavePointOnly] is the escape hatch for a single point.
 */
@Composable
fun PlaceNameDialog(
    initial: String,
    pointCount: Int,
    spanM: Double,
    named: Boolean,
    /** Members that already carry a name of their own and would outrank it. */
    overriddenCount: Int,
    /** Roads inferred for this crossing; the user confirms before saving. */
    roadCandidates: List<String> = emptyList(),
    /** Roads already chosen, so reopening the dialog does not lose them. */
    initialRoads: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Set<String>) -> Unit,
    onSavePointOnly: (String, Set<String>) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var roads by remember(initialRoads) { mutableStateOf(initialRoads) }
    var takeOverNames by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.place_name_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.place_name_hint)) },
                )
                if (pointCount > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.place_summary, pointCount, formatDistance(spanM)),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.place_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Points named one by one before junctions had names keep
                // their own label; folding them in is offered, never assumed.
                if (overriddenCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.place_overridden,
                                    overriddenCount,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = stringResource(R.string.place_overridden_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = takeOverNames, onCheckedChange = { takeOverNames = it })
                    }
                }
                Spacer(Modifier.height(10.dp))
                PointRoadsPicker(roadCandidates, roads) { road ->
                    roads = if (road in roads) roads - road else roads + road
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.place_clear_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pointCount > 1) {
                    TextButton(onClick = { onSavePointOnly(text, roads) }) {
                        Text(stringResource(R.string.place_only_point))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, takeOverNames, roads) }) {
                Text(
                    stringResource(
                        if (named) R.string.action_save else R.string.place_name_apply,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Asks for the road name of one segment. Shared by the segment screen and the
 * trip's leg overlay, which both offer the same action.
 *
 * This name is inference input for the points the segment touches; the segment
 * itself is displayed by its two atomic end names. Saving an empty field drops
 * the name from point-naming suggestions, so the dialog says so instead of
 * disabling the button.
 */
@Deprecated("Use EdgeNameDialog", ReplaceWith("EdgeNameDialog(initial, osmName, onDismiss, onSave, title, hint, clearHint, reuse)"))
@Composable
fun SegmentNameDialog(
    initial: String,
    osmName: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    title: String? = null,
    hint: String? = null,
    clearHint: String? = null,
    reuse: List<String> = emptyList(),
) = EdgeNameDialog(initial, osmName, onDismiss, onSave, title, hint, clearHint, reuse)

@Composable
fun EdgeNameDialog(
    initial: String,
    osmName: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    title: String? = null,
    hint: String? = null,
    clearHint: String? = null,
    /** Road names already used elsewhere; one tap fills the field. */
    reuse: List<String> = emptyList(),
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.edge_name)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(hint ?: stringResource(R.string.edge_name_hint)) },
                )
                val suggestion = osmName?.takeIf { it.isNotBlank() && it != text }
                if (suggestion != null) {
                    TextButton(onClick = { text = suggestion }) {
                        Text(stringResource(R.string.edge_name_use, suggestion))
                    }
                }
                RoadNameReuse(reuse, text) { text = it }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = clearHint ?: stringResource(R.string.edge_name_clear_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * Road names already given elsewhere, offered as one-tap chips. A road is many
 * pieces; typing its name once and reusing it keeps every piece spelled the
 * same. Shared by both places a segment can be named.
 */
@Composable
fun RoadNameReuse(
    names: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    if (names.isEmpty()) return
    Text(
        text = stringResource(R.string.edge_name_reuse),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
            FilterChip(
                selected = name == current,
                onClick = { onPick(name) },
                label = { Text(name) },
            )
        }
    }
}
