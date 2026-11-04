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
    /** [label] is a string resource; store apps have their own [title] instead. */
    data class App(
        val id: String,
        val label: Int,
        val command: String,
        val icon: Int,
        val title: String? = null,
        /** Store apps: the Ubuntu packages that install it. */
        val packages: String? = null,
    ) {
        fun name(context: Context) = title ?: context.getString(label)
    }

    /** Built into SpirOS Linux. */
    val all = listOf(
        App("mt5", R.string.lapp_mt5, "spiros-mt5", R.drawable.ic_sc_mt5),
        App("mt4", R.string.lapp_mt4, "spiros-mt4", R.drawable.ic_sc_mt4),
        App("terminal", R.string.lapp_terminal, "lxterminal", R.drawable.ic_sc_terminal),
        App("files", R.string.lapp_files, "pcmanfm", R.drawable.ic_sc_files),
        App("ai", R.string.lapp_ai, "lxterminal -t 'AI Chat' -e spiros-ai", R.drawable.ic_sc_aichat),
    )

    /** Built-in apps plus apps installed from the SpirOS store. */
    fun installed(context: Context, store: Map<String, String> = LinuxStore.installed.value) =
        all + LinuxStore.apps(context, store)

    fun byId(id: String) = all.firstOrNull { it.id == id }

    fun byId(context: Context, id: String): App? {
        LinuxStore.load(context)
        return installed(context).firstOrNull { it.id == id }
    }

    /** An app to open as soon as the Linux screen and the engine are ready. */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    fun request(id: String) { _pending.value = id }
    fun consume() { _pending.value = null }

    /** Windows programs (MetaTrader 5 / 4 full): installed by the engine on first use. */
    val ADDONS = setOf("mt5", "mt4")

    /** A program asked for while its package was still downloading: opened when it is ready. */
    @Volatile private var afterInstall: Pair<Context, App>? = null

    /** True while [id]'s package is being downloaded / installed. */
    fun addonInstalling(status: Engine.Status, id: String) =
        status.task?.let { it.kind == "addon" && it.name == id && it.active } == true

    internal fun onStatus(status: Engine.Status) {
        val (context, app) = afterInstall ?: return
        if (status.addonInstalled(app.id) && !addonInstalling(status, app.id)) {
            afterInstall = null
            launch(context, app)
        } else if (status.task?.let { it.kind == "addon" && !it.active && it.error != null } == true) {
            afterInstall = null
        }
    }

    /**
     * Opens [app] inside the running desktop, or alone and full screen if none runs.
     * MetaTrader 5 / 4 first download their full package (Wine for ARM64 + the
     * terminal) in the background, then open by themselves.
     */
    fun launch(context: Context, app: App) {
        val status = Engine.status.value
        if (app.id in ADDONS && status.connected && !status.addonInstalled(app.id) && status.arch == "arm64") {
            val appContext = context.applicationContext
            if (addonInstalling(status, app.id) || (("addon" to app.id) in status.queue)) {
                afterInstall = appContext to app // already on its way: just open it when ready
                return
            }
            // Ask first (size, free space, Wi-Fi), then download in the background.
            LinuxStore.ask(
                LinuxStore.Confirm(app.name(context), LinuxStore.addonDownloadSize(status, LinuxStore.published.value, app.id)) {
                    afterInstall = appContext to app
                    Engine.installAddon(appContext, app.id)
                },
            )
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
            .setShortLabel(app.name(context))
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
