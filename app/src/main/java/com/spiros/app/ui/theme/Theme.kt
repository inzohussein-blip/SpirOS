package com.spiros.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val SpirNight = Color(0xFF0B0F1A)
val SpirSurface = Color(0xFF151B2B)
val SpirAccent = Color(0xFF7C5CFF)
val SpirMint = Color(0xFF3DDC97)

/** One colour per SpirOS tool, used for its tile, chips and shortcuts. */
object ToolColors {
    val Vault = Color(0xFF7C5CFF)
    val Browser = Color(0xFF3B82F6)
    val Shield = Color(0xFF22C55E)
    val Space = Color(0xFFF59E0B)
    val Linux = Color(0xFFEF4444)
    val Ai = Color(0xFFEC4899)
    val Lab = Color(0xFF14B8A6)
    val Settings = Color(0xFF64748B)

    fun gradient(base: Color) = Brush.linearGradient(listOf(base, base.copy(alpha = 0.55f)))
}

/** Whether the current style uses gradients and glow (Elegant) or flat surfaces (Minimal). */
val LocalElegant = compositionLocalOf { true }

private val SpirDark = darkColorScheme(
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

private val SpirLight = lightColorScheme(
    primary = Color(0xFF5B3FE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DFFF),
    onPrimaryContainer = Color(0xFF1C0C64),
    secondary = Color(0xFF0E8F5F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7F2DF),
    onSecondaryContainer = Color(0xFF002114),
    tertiary = Color(0xFFC0266F),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF151824),
    surface = Color.White,
    onSurface = Color(0xFF151824),
    surfaceVariant = Color(0xFFECEEF5),
    onSurfaceVariant = Color(0xFF5B6275),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9FAFD),
    surfaceContainer = Color(0xFFF2F3F8),
    surfaceContainerHigh = Color(0xFFECEEF5),
    surfaceContainerHighest = Color(0xFFE6E8F0),
    outline = Color(0xFFC3C7D4),
    outlineVariant = Color(0xFFDDE0EA),
    error = Color(0xFFD32F2F),
)

/** AMOLED black (pixels off on OLED screens) with one neon accent. */
private fun cyberScheme(neon: Color) = darkColorScheme(
    primary = neon,
    onPrimary = Color.Black,
    primaryContainer = neon.copy(alpha = 0.18f),
    onPrimaryContainer = neon,
    secondary = neon,
    onSecondary = Color.Black,
    secondaryContainer = neon.copy(alpha = 0.14f),
    onSecondaryContainer = neon,
    tertiary = Color(0xFFFF2BD6),
    background = Color.Black,
    onBackground = Color(0xFFD7FFE9),
    surface = Color(0xFF050807),
    onSurface = Color(0xFFD7FFE9),
    surfaceVariant = Color(0xFF0B1210),
    onSurfaceVariant = neon.copy(alpha = 0.7f),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF030504),
    surfaceContainer = Color(0xFF060A08),
    surfaceContainerHigh = Color(0xFF0A100D),
    surfaceContainerHighest = Color(0xFF0E1612),
    surfaceBright = Color(0xFF121C17),
    surfaceDim = Color.Black,
    outline = neon.copy(alpha = 0.45f),
    outlineVariant = neon.copy(alpha = 0.2f),
    error = Color(0xFFFF3B5C),
)

val CyberGreen = Color(0xFF39FF88)
val CyberCyan = Color(0xFF00E5FF)

private val SpirShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SpirTheme(content: @Composable () -> Unit) {
    val state by Appearance.state.collectAsState()
    val cyber = state.palette == Appearance.Palette.CYBER_GREEN || state.palette == Appearance.Palette.CYBER_CYAN
    val dark = cyber || when (state.mode) {
        Appearance.Mode.SYSTEM -> isSystemInDarkTheme()
        Appearance.Mode.DARK -> true
        Appearance.Mode.LIGHT -> false
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        state.palette == Appearance.Palette.CYBER_GREEN -> cyberScheme(CyberGreen)
        state.palette == Appearance.Palette.CYBER_CYAN -> cyberScheme(CyberCyan)
        state.palette == Appearance.Palette.MATERIAL_YOU && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> SpirDark
        else -> SpirLight
    }

    // Status/navigation bar icons follow the app's theme, not only the system's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalElegant provides (state.style == Appearance.Style.ELEGANT)) {
        MaterialTheme(colorScheme = scheme, shapes = SpirShapes, content = content)
    }
}
