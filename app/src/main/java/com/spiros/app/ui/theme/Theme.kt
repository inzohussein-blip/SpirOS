package com.spiros.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SpirNight = Color(0xFF0B0F1A)
val SpirSurface = Color(0xFF151B2B)
val SpirAccent = Color(0xFF7C5CFF)
val SpirMint = Color(0xFF3DDC97)

private val SpirColors = darkColorScheme(
    primary = SpirAccent,
    secondary = SpirMint,
    background = SpirNight,
    surface = SpirSurface,
    surfaceVariant = SpirSurface,
    onPrimary = Color.White,
    onBackground = Color(0xFFE6E8F0),
    onSurface = Color(0xFFE6E8F0),
    onSurfaceVariant = Color(0xFF9AA3B8),
)

@Composable
fun SpirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpirColors, content = content)
}
