package com.geno1024.ai.mapoftime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun MapOfTimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
