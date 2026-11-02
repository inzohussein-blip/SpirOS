package com.spiros.app.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import com.spiros.app.R
import com.spiros.app.perf.Performance
import com.spiros.app.ui.Navigation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Linux programs SpirOS can open directly (from Home, the Linux screen, or a shortcut
 * on the phone's home screen), e.g. MetaTrader 5 full screen without the desktop.
 */
object LinuxApps {
    data class App(val id: String, val label: Int, val command: String, val icon: Int)

    val all = listOf(
        App("mt5", R.string.lapp_mt5, "spiros-mt5", R.drawable.ic_sc_mt5),
        App("terminal", R.string.lapp_terminal, "lxterminal", R.drawable.ic_sc_terminal),
        App("files", R.string.lapp_files, "pcmanfm", R.drawable.ic_sc_files),
        App("ai", R.string.lapp_ai, "lxterminal -t 'AI Chat' -e spiros-ai", R.drawable.ic_sc_aichat),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** An app to open as soon as the Linux screen and the engine are ready. */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    fun request(id: String) { _pending.value = id }
    fun consume() { _pending.value = null }

    /** MT5 asked for while its package was still downloading: opened when it is ready. */
    @Volatile private var afterInstall: Pair<Context, App>? = null

    /** True while MetaTrader 5 full is being downloaded / installed. */
    fun mt5Installing(status: Engine.Status) = status.task?.let { it.kind == "mt5" && it.active } == true

    internal fun onStatus(status: Engine.Status) {
        val (context, app) = afterInstall ?: return
        if (status.mt5Installed) {
            afterInstall = null
            launch(context, app)
        } else if (!mt5Installing(status) && status.task?.kind == "mt5" && status.task.error != null) {
            afterInstall = null
        }
    }

    /**
     * Opens [app] inside the running desktop, or alone and full screen if none runs.
     * MetaTrader 5 first downloads its full package (Wine + MT5 installed) in the
     * background, then opens by itself.
     */
    fun launch(context: Context, app: App) {
        val status = Engine.status.value
        if (app.id == "mt5" && status.connected && !status.mt5Installed && status.arch == "arm64") {
            afterInstall = context.applicationContext to app
            if (!mt5Installing(status)) Engine.installMt5(context)
            android.widget.Toast.makeText(context, R.string.mt5_installing, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (Engine.status.value.desktopRunning) {
            Engine.send(context) { it.runInDesktop(app.command) }
        } else {
            val single = Performance.resolve(context, Performance.linuxResolution(context))
            Engine.startDesktop(
                context, single, dpi = 120,
                light = Performance.linuxLight(context),
                singleApp = app.command,
                fps = Performance.linuxFps(context),
            )
        }
    }

    fun shortcut(context: Context, app: App): ShortcutInfo {
        val intent = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(context.packageName, "com.spiros.app.Launcher"))
            .putExtra(Navigation.EXTRA, Navigation.LINUX_APP + app.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return ShortcutInfo.Builder(context, Navigation.LINUX_APP + app.id)
            .setShortLabel(context.getString(app.label))
            .setIcon(Icon.createWithResource(context, app.icon))
            .setIntent(intent)
            .build()
    }

    /** Asks the phone's launcher to add [app] to the home screen; false if not supported. */
    fun pinToHomeScreen(context: Context, app: App): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        return runCatching { manager.requestPinShortcut(shortcut(context, app), null) }.getOrDefault(false)
    }
}
