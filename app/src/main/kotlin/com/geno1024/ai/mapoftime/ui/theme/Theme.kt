package com.geno1024.ai.mapoftime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.geno1024.ai.mapoftime.data.AppMode

private val Ink = Color(0xFF0E1116)
private val Surface = Color(0xFF151B23)
private val SurfaceHigh = Color(0xFF1E2631)
private val Sky = Color(0xFF4FC3F7)
private val Amber = Color(0xFFFFB74D)
private val Coral = Color(0xFFEF6C60)

private val DarkColors = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF00202E),
    primaryContainer = Color(0xFF00384D),
    onPrimaryContainer = Color(0xFFB8E8FF),
    secondary = Amber,
    onSecondary = Color(0xFF3A2500),
    background = Ink,
    onBackground = Color(0xFFE6EDF3),
    surface = Surface,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = Color(0xFFB5C0CC),
    error = Coral,
    outline = Color(0xFF3A4552),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4E7F8),
    onPrimaryContainer = Color(0xFF001E2B),
    secondary = Color(0xFF8A5200),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF191C1F),
    surface = Color.White,
    onSurface = Color(0xFF191C1F),
    surfaceVariant = Color(0xFFE3E8EE),
    onSurfaceVariant = Color(0xFF41484F),
    error = Color(0xFFB3261E),
    outline = Color(0xFFC3CBD3),
)

/**
 * A separate scheme for the aggregated shell: graphite instead of blue steel,
 * teal/sand accents instead of sky/amber. Shared components and the map read
 * these slots, so the same code can look like a different app.
 */
private val AggregatedColors = darkColorScheme(
    primary = Color(0xFF2A9D8F),
    onPrimary = Color(0xFF07231F),
    primaryContainer = Color(0xFF17463F),
    onPrimaryContainer = Color(0xFFB8EBE2),
    secondary = Color(0xFFE9C46A),
    onSecondary = Color(0xFF2E2306),
    background = Color(0xFF0B1014),
    onBackground = Color(0xFFE8F0F2),
    surface = Color(0xFF121C21),
    onSurface = Color(0xFFE8F0F2),
    surfaceVariant = Color(0xFF1B2A31),
    onSurfaceVariant = Color(0xFFA8B9BE),
    error = Color(0xFFE4562E),
    outline = Color(0xFF2C4048),
)

@Composable
fun MapOfTimeTheme(
    appMode: AppMode = AppMode.CLASSIC,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = when (appMode) {
            AppMode.CLASSIC -> if (isSystemInDarkTheme()) DarkColors else LightColors
            AppMode.GRAPH -> AggregatedColors
        },
        content = content,
    )
}
