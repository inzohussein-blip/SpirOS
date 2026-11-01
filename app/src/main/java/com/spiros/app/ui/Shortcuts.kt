package com.spiros.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.spiros.app.BuildConfig
import com.spiros.app.R

/**
 * Long-press shortcuts on the SpirOS launcher icon (Vault, Browser, Linux, Shield).
 * Removed in disguise mode, where they would give the app away.
 */
object Shortcuts {
    fun update(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (Disguise.isEnabled(context)) {
            manager.removeAllDynamicShortcuts()
            return
        }
        val items = buildList {
            add(shortcut(context, Navigation.VAULT, R.string.section_vault, R.drawable.ic_sc_vault))
            add(shortcut(context, Navigation.BROWSER, R.string.dock_browser, R.drawable.ic_sc_browser))
            if (BuildConfig.FLAVOR == "speed") add(shortcut(context, Navigation.LINUX, R.string.dock_linux, R.drawable.ic_sc_linux))
            add(shortcut(context, Navigation.SHIELD, R.string.section_shield, R.drawable.ic_sc_shield))
        }.take(manager.maxShortcutCountPerActivity.coerceAtLeast(1))
        runCatching { manager.dynamicShortcuts = items }
    }

    private fun shortcut(context: Context, id: String, label: Int, icon: Int): ShortcutInfo {
        val intent = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(context.packageName, "com.spiros.app.Launcher"))
            .putExtra(Navigation.EXTRA, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(context.getString(label))
            .setIcon(Icon.createWithResource(context, icon))
            .setIntent(intent)
            .build()
    }
}
