package com.spiros.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val SpirNight = Color(0xFF0B0F1A)
val SpirSurface = Color(0xFF151B2B)
val SpirAccent = Color(0xFF7C5CFF)
val SpirMint = Color(0xFF3DDC97)

/** One colour per SpirOS tool, used for its tile and chips. */
object ToolColors {
    val Vault = Color(0xFF7C5CFF)
    val Browser = Color(0xFF3B82F6)
    val Shield = Color(0xFF3DDC97)
    val Space = Color(0xFFF59E0B)
    val Linux = Color(0xFFEF4444)
    val Ai = Color(0xFFEC4899)
    val Lab = Color(0xFF14B8A6)
    val Settings = Color(0xFF64748B)

    fun gradient(base: Color) = Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f)))
}

private val SpirColors = darkColorScheme(
    primary = SpirAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2160),
    onPrimaryContainer = Color(0xFFE4DDFF),
    secondary = SpirMint,
    onSecondary = Color(0xFF00281A),
    secondaryContainer = Color(0xFF123B2D),
    onSecondaryContainer = Color(0xFFB8F5DC),
    tertiary = Color(0xFFEC4899),
    background = SpirNight,
    onBackground = Color(0xFFE6E8F0),
    surface = SpirSurface,
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF1C2336),
    onSurfaceVariant = Color(0xFF9AA3B8),
    // Material 3 cards, sheets and dialogs use the container roles: keep them on-brand.
    surfaceContainerLowest = Color(0xFF080B14),
    surfaceContainerLow = Color(0xFF111626),
    surfaceContainer = Color(0xFF151B2B),
    surfaceContainerHigh = Color(0xFF1B2235),
    surfaceContainerHighest = Color(0xFF222A3F),
    surfaceBright = Color(0xFF283048),
    surfaceDim = SpirNight,
    outline = Color(0xFF3A4360),
    outlineVariant = Color(0xFF283048),
    error = Color(0xFFFF6B6B),
)

private val SpirShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SpirTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SpirColors, shapes = SpirShapes, content = content)
}
