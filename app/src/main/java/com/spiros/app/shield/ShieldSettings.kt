package com.spiros.app.shield

import android.content.Context

/** Shield preferences. Each profile (phone and space) keeps its own copy. */
object ShieldSettings {
    enum class Upstream(val address: String, val label: String) {
        QUAD9("9.9.9.9", "Quad9"),
        CLOUDFLARE("1.1.1.1", "Cloudflare"),
        ADGUARD("94.140.14.14", "AdGuard"),
    }

    private const val PREFS = "shield"
    private const val KEY_UPSTREAM = "upstream"
    private const val KEY_FULL = "full_list"
    private const val KEY_SPACE_SHIELD = "space_shield"
    private const val KEY_SPACE_GOOGLE_OFF = "space_google_off"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun upstream(context: Context): Upstream =
        runCatching { Upstream.valueOf(prefs(context).getString(KEY_UPSTREAM, null)!!) }.getOrDefault(Upstream.QUAD9)

    fun setUpstream(context: Context, value: Upstream) = prefs(context).edit().putString(KEY_UPSTREAM, value.name).apply()

    fun useFullList(context: Context) = prefs(context).getBoolean(KEY_FULL, false)

    fun setUseFullList(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_FULL, value).apply()

    // What the main profile last asked the space to do (the space cannot report back).
    fun spaceShield(context: Context) = prefs(context).getBoolean(KEY_SPACE_SHIELD, false)

    fun setSpaceShield(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SPACE_SHIELD, value).apply()

    fun spaceGoogleOff(context: Context) = prefs(context).getBoolean(KEY_SPACE_GOOGLE_OFF, false)

    fun setSpaceGoogleOff(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_SPACE_GOOGLE_OFF, value).apply()
}
