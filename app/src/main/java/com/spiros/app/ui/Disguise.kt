package com.spiros.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Disguise mode: SpirOS appears in the app drawer as a working calculator.
 * Typing the PIN and "=" opens it. Only the launcher entry changes; the app is the same.
 */
object Disguise {
    private const val PREFS = "ui"
    private const val KEY = "disguise"
    private const val NORMAL = "com.spiros.app.Launcher"
    private const val CALCULATOR = "com.spiros.app.Calculator"

    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        val pm = context.packageManager
        // Enable the new entry before disabling the old one so there is always a launcher icon.
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, if (enabled) CALCULATOR else NORMAL),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, if (enabled) NORMAL else CALCULATOR),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
