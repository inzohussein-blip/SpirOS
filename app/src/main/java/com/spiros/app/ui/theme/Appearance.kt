package com.spiros.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Appearance choices from Settings; changes apply live to the whole app. */
object Appearance {
    enum class Mode { SYSTEM, DARK, LIGHT }
    /** CYBER_*: pure AMOLED black with a neon accent (always dark). */
    /** OBSIDIAN: charcoal with a golden-orange glow; NEON_INDIGO: deep indigo with a neon-green action colour. */
    enum class Palette { SPIROS, MATERIAL_YOU, CYBER_GREEN, CYBER_CYAN, OBSIDIAN, NEON_INDIGO }
    enum class Style { ELEGANT, MINIMAL }
    /** SYSTEM: the phone's font everywhere; INTER: the Inter typeface for titles and Latin text. */
    enum class Font { SYSTEM, INTER }

    data class State(val mode: Mode, val palette: Palette, val style: Style, val stealthLock: Boolean = false, val font: Font = Font.SYSTEM)

    private const val PREFS = "appearance"
    private val _state = MutableStateFlow(State(Mode.DARK, Palette.SPIROS, Style.ELEGANT))
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = State(
            mode = enumOr(p.getString("mode", null), Mode.DARK),
            palette = enumOr(p.getString("palette", null), Palette.SPIROS),
            style = enumOr(p.getString("style", null), Style.ELEGANT),
            stealthLock = p.getBoolean("stealth_lock", false),
            font = enumOr(p.getString("font", null), Font.SYSTEM),
        )
    }

    fun update(context: Context, transform: (State) -> State) {
        val next = transform(_state.value)
        _state.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", next.mode.name)
            .putString("palette", next.palette.name)
            .putString("style", next.style.name)
            .putBoolean("stealth_lock", next.stealthLock)
            .putString("font", next.font.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        runCatching { enumValueOf<T>(name!!) }.getOrDefault(fallback)
}
