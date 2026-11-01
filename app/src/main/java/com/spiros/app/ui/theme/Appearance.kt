package com.spiros.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Appearance choices from Settings; changes apply live to the whole app. */
object Appearance {
    enum class Mode { SYSTEM, DARK, LIGHT }
    /** CYBER_*: pure AMOLED black with a neon accent (always dark). */
    enum class Palette { SPIROS, MATERIAL_YOU, CYBER_GREEN, CYBER_CYAN }
    enum class Style { ELEGANT, MINIMAL }

    data class State(val mode: Mode, val palette: Palette, val style: Style, val stealthLock: Boolean = false)

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
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        runCatching { enumValueOf<T>(name!!) }.getOrDefault(fallback)
}
