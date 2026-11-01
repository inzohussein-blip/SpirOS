package com.spiros.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Disguise mode: SpirOS appears in the app drawer as a working calculator.
 * Typing the PIN and "=" opens it. Only the launcher entry changes; the app is the same.
 */
object Disguise {
    private const val PREFS = "ui"
    private const val KEY = "disguise"
    private const val NORMAL = "com.spiros.app.Launcher"
    private const val CALCULATOR = "com.spiros.app.Calculator"

    private const val KEY_CODE = "secret_code_sha256"

    /** Calculator keys a secret code may use; keyboard -, *, / map to the calculator's signs. */
    fun normalizeCode(raw: String): String =
        raw.trim().replace('-', '−').replace('*', '×').replace('/', '÷').filter { it.isDigit() || it in ".+−×÷" }

    fun isValidCode(code: String) = code.length >= 4

    fun hasSecretCode(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CODE, null) != null

    /** Stores only a hash of the code (null removes it). */
    fun setSecretCode(context: Context, code: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (code == null) remove(KEY_CODE) else putString(KEY_CODE, sha256(code))
        }.apply()
    }

    fun matchesSecretCode(context: Context, entry: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CODE, null) ?: return false
        return MessageDigest.isEqual(stored.toByteArray(), sha256(entry).toByteArray())
    }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(("spiros-calc:" + text).toByteArray()).joinToString("") { "%02x".format(it) }

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
        // Shortcuts and the Quick Settings tile would give the disguise away.
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, "com.spiros.app.shield.ShieldTileService"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, "com.spiros.app.security.PanicTileService"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
        Shortcuts.update(context)
    }
}
