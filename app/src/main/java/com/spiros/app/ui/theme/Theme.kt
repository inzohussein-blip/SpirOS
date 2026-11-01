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
import androidx.compose.ui.unit.sp
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

/** Charcoal with a golden-orange glow (reference: dark fintech / security sites). */
private val ObsidianScheme = darkColorScheme(
    primary = Color(0xFFFB6A0C),
    onPrimary = Color(0xFF1A0A00),
    primaryContainer = Color(0xFF3A1E0A),
    onPrimaryContainer = Color(0xFFFFD2B0),
    secondary = Color(0xFFF8CC4B),
    onSecondary = Color(0xFF221800),
    secondaryContainer = Color(0xFF332A12),
    onSecondaryContainer = Color(0xFFFFE9A8),
    tertiary = Color(0xFFCDA07D),
    background = Color(0xFF0F0F10),
    onBackground = Color(0xFFECE7E2),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFECE7E2),
    surfaceVariant = Color(0xFF222226),
    onSurfaceVariant = Color(0xFFA9A39C),
    surfaceContainerLowest = Color(0xFF0A0A0B),
    surfaceContainerLow = Color(0xFF131315),
    surfaceContainer = Color(0xFF17171A),
    surfaceContainerHigh = Color(0xFF1D1D21),
    surfaceContainerHighest = Color(0xFF242428),
    surfaceBright = Color(0xFF2B2B30),
    surfaceDim = Color(0xFF0F0F10),
    outline = Color(0xFF4A4642),
    outlineVariant = Color(0xFF2E2C2A),
    error = Color(0xFFFF5D4F),
)

/** Deep indigo with a neon-green action colour (reference: dark AI-tool sites). */
private val NeonIndigoScheme = darkColorScheme(
    primary = Color(0xFF04F17E),
    onPrimary = Color(0xFF00210F),
    primaryContainer = Color(0xFF0B3D2A),
    onPrimaryContainer = Color(0xFFB8FFD9),
    secondary = Color(0xFFB88CFB),
    onSecondary = Color(0xFF240A4D),
    secondaryContainer = Color(0xFF34216B),
    onSecondaryContainer = Color(0xFFE8DBFF),
    tertiary = Color(0xFF7C8CFF),
    background = Color(0xFF0D0B26),
    onBackground = Color(0xFFE9E6FF),
    surface = Color(0xFF15123A),
    onSurface = Color(0xFFE9E6FF),
    surfaceVariant = Color(0xFF1E1A4D),
    onSurfaceVariant = Color(0xFFA7A2D8),
    surfaceContainerLowest = Color(0xFF09081C),
    surfaceContainerLow = Color(0xFF110F30),
    surfaceContainer = Color(0xFF15123A),
    surfaceContainerHigh = Color(0xFF1B1846),
    surfaceContainerHighest = Color(0xFF221E52),
    surfaceBright = Color(0xFF2A255E),
    surfaceDim = Color(0xFF0D0B26),
    outline = Color(0xFF4B4690),
    outlineVariant = Color(0xFF2C2866),
    error = Color(0xFFFF6B8B),
)

/** Inter for titles and Latin text; Arabic falls back to the phone's own font. */
val InterFamily = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(com.spiros.app.R.font.inter_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.spiros.app.R.font.inter_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.spiros.app.R.font.inter_semibold, androidx.compose.ui.text.font.FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(com.spiros.app.R.font.inter_bold, androidx.compose.ui.text.font.FontWeight.Bold),
)

private fun interTypography(): androidx.compose.material3.Typography {
    val base = androidx.compose.material3.Typography()
    fun androidx.compose.ui.text.TextStyle.inter(weight: androidx.compose.ui.text.font.FontWeight? = null) =
        copy(fontFamily = InterFamily, fontWeight = weight ?: fontWeight, letterSpacing = if (fontSize.value >= 22f) (-0.4).sp else letterSpacing)
    val semi = androidx.compose.ui.text.font.FontWeight.SemiBold
    return base.copy(
        displayLarge = base.displayLarge.inter(semi), displayMedium = base.displayMedium.inter(semi), displaySmall = base.displaySmall.inter(semi),
        headlineLarge = base.headlineLarge.inter(semi), headlineMedium = base.headlineMedium.inter(semi), headlineSmall = base.headlineSmall.inter(semi),
        titleLarge = base.titleLarge.inter(semi), titleMedium = base.titleMedium.inter(), titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter(), bodyMedium = base.bodyMedium.inter(), bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(), labelMedium = base.labelMedium.inter(), labelSmall = base.labelSmall.inter(),
    )
}

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
    val cyber = state.palette == Appearance.Palette.CYBER_GREEN || state.palette == Appearance.Palette.CYBER_CYAN ||
        state.palette == Appearance.Palette.OBSIDIAN || state.palette == Appearance.Palette.NEON_INDIGO
    val dark = cyber || when (state.mode) {
        Appearance.Mode.SYSTEM -> isSystemInDarkTheme()
        Appearance.Mode.DARK -> true
        Appearance.Mode.LIGHT -> false
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        state.palette == Appearance.Palette.CYBER_GREEN -> cyberScheme(CyberGreen)
        state.palette == Appearance.Palette.CYBER_CYAN -> cyberScheme(CyberCyan)
        state.palette == Appearance.Palette.OBSIDIAN -> ObsidianScheme
        state.palette == Appearance.Palette.NEON_INDIGO -> NeonIndigoScheme
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
        MaterialTheme(
            colorScheme = scheme,
            shapes = SpirShapes,
            typography = if (state.font == Appearance.Font.INTER) interTypography() else androidx.compose.material3.Typography(),
            content = content,
        )
    }
}
