package com.spiros.app.perf

import android.content.Context
import java.io.File

/** "Performance & battery" preferences, read by the Linux desktop, the AI and the space. */
object Performance {
    private const val PREFS = "performance"

    enum class Session { DESKTOP, SINGLE_APP }

    /** Programs offered for single-app mode (command inside Linux). */
    val SINGLE_APPS = listOf("x-terminal-emulator", "pcmanfm", "thunar", "caja", "nemo", "mousepad", "pluma", "spiros-mt5", "spiros-mt4")

    /** "auto" = the phone's own screen shape (fills it in landscape, no black bars). */
    const val AUTO = "auto"
    val RESOLUTIONS = listOf(AUTO, "960x540", "1280x720", "1600x900", "1920x1080")

    /** Desktop size with this phone's aspect ratio, 720 pixels high (light and sharp). */
    fun phoneGeometry(c: Context): String {
        val bounds = c.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val long = maxOf(bounds.width(), bounds.height()).toFloat()
        val short = minOf(bounds.width(), bounds.height()).toFloat().coerceAtLeast(1f)
        val w = (720 * long / short).toInt().coerceIn(960, 1920)
        return "${w - w % 8}x720"
    }

    fun resolve(c: Context, geometry: String) = if (geometry == AUTO) phoneGeometry(c) else geometry

    /** Desktop shown sideways and full screen (system bars hidden). */
    fun linuxLandscape(c: Context) = prefs(c).getBoolean("linux_landscape", false)
    fun setLinuxLandscape(c: Context, v: Boolean) = prefs(c).edit().putBoolean("linux_landscape", v).apply()
    val FPS_OPTIONS = listOf(15, 30, 60)
    val CONTEXT_OPTIONS = listOf(1024, 2048, 4096)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Linux ----
    fun linuxLight(c: Context) = prefs(c).getBoolean("linux_light", true)
    fun setLinuxLight(c: Context, v: Boolean) = prefs(c).edit().putBoolean("linux_light", v).apply()

    fun linuxFps(c: Context) = prefs(c).getInt("linux_fps", 30)
    fun setLinuxFps(c: Context, v: Int) = prefs(c).edit().putInt("linux_fps", v).apply()

    fun linuxResolution(c: Context) = prefs(c).getString("linux_resolution", AUTO)!!
    fun setLinuxResolution(c: Context, v: String) = prefs(c).edit().putString("linux_resolution", v).apply()

    fun freezeInBackground(c: Context) = prefs(c).getBoolean("freeze_background", true)
    fun setFreezeInBackground(c: Context, v: Boolean) = prefs(c).edit().putBoolean("freeze_background", v).apply()

    fun linuxSession(c: Context) = runCatching { Session.valueOf(prefs(c).getString("linux_session", null)!!) }.getOrDefault(Session.DESKTOP)
    fun setLinuxSession(c: Context, v: Session) = prefs(c).edit().putString("linux_session", v.name).apply()

    fun singleApp(c: Context) = prefs(c).getString("single_app", SINGLE_APPS.first())!!
    fun setSingleApp(c: Context, v: String) = prefs(c).edit().putString("single_app", v).apply()

    fun pip(c: Context) = prefs(c).getBoolean("pip", true)
    fun setPip(c: Context, v: Boolean) = prefs(c).edit().putBoolean("pip", v).apply()

    /** Show the Linux desktop on a connected TV / monitor (HDMI or wireless). */
    fun externalDisplay(c: Context) = prefs(c).getBoolean("external_display", true)
    fun setExternalDisplay(c: Context, v: Boolean) = prefs(c).edit().putBoolean("external_display", v).apply()

    /** Start the desktop at the external screen's resolution. */
    fun externalMatch(c: Context) = prefs(c).getBoolean("external_match", true)
    fun setExternalMatch(c: Context, v: Boolean) = prefs(c).edit().putBoolean("external_match", v).apply()

    /** Touchpad under the Linux screen (button in the desktop's top bar). */
    fun touchpad(c: Context) = prefs(c).getBoolean("touchpad", false)
    fun setTouchpad(c: Context, v: Boolean) = prefs(c).edit().putBoolean("touchpad", v).apply()

    /** The Linux setup's "create your account" step was done or skipped. */
    fun accountPromptDone(c: Context) = prefs(c).getBoolean("account_prompt_done", false)
    fun setAccountPromptDone(c: Context, v: Boolean) = prefs(c).edit().putBoolean("account_prompt_done", v).apply()

    /** Vault shown as a grid (true) or a list. */
    fun vaultGrid(c: Context) = prefs(c).getBoolean("vault_grid", true)
    fun setVaultGrid(c: Context, v: Boolean) = prefs(c).edit().putBoolean("vault_grid", v).apply()

    // ---- AI ----
    fun aiFastCores(c: Context) = prefs(c).getBoolean("ai_fast_cores", true)
    fun setAiFastCores(c: Context, v: Boolean) = prefs(c).edit().putBoolean("ai_fast_cores", v).apply()

    fun aiContext(c: Context) = prefs(c).getInt("ai_context", 2048)
    fun setAiContext(c: Context, v: Int) = prefs(c).edit().putInt("ai_context", v).apply()

    /**
     * Number of "big" cores (max frequency within 80% of the fastest). Mixing in the
     * slow efficiency cores makes llama.cpp wait for them on every step.
     */
    fun fastCoreCount(): Int {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.isEmpty()) return (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        val top = freqs.max()
        return freqs.count { it >= top * 0.8 }.coerceAtLeast(1)
    }

    fun aiThreads(c: Context): Int =
        if (aiFastCores(c)) fastCoreCount() else Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
}
