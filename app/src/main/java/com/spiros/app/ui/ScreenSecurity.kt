package com.spiros.app.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager

/**
 * Whether screenshots, screen recording and the recents preview are allowed.
 * Off by default (FLAG_SECURE); the user can allow them in Settings.
 */
object ScreenSecurity {
    private const val PREFS = "ui"
    private const val KEY = "allow_screenshots"

    fun screenshotsAllowed(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setScreenshotsAllowed(context: Context, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, allowed).apply()
        (context as? Activity)?.let(::apply)
    }

    fun apply(activity: Activity) {
        if (screenshotsAllowed(activity)) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
