package com.spiros.app.perf

import android.content.Context
import java.io.File

/** "Performance & battery" preferences, read by the Linux desktop, the AI and the space. */
object Performance {
    private const val PREFS = "performance"

    enum class Session { DESKTOP, SINGLE_APP }

    /** Programs offered for single-app mode (command inside Linux). */
    val SINGLE_APPS = listOf("x-terminal-emulator", "thunar", "caja", "nemo", "mousepad", "pluma")

    val RESOLUTIONS = listOf("960x540", "1280x720", "1600x900", "1920x1080")
    val FPS_OPTIONS = listOf(15, 30, 60)
    val CONTEXT_OPTIONS = listOf(1024, 2048, 4096)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Linux ----
    fun linuxLight(c: Context) = prefs(c).getBoolean("linux_light", true)
    fun setLinuxLight(c: Context, v: Boolean) = prefs(c).edit().putBoolean("linux_light", v).apply()

    fun linuxFps(c: Context) = prefs(c).getInt("linux_fps", 30)
    fun setLinuxFps(c: Context, v: Int) = prefs(c).edit().putInt("linux_fps", v).apply()

    fun linuxResolution(c: Context) = prefs(c).getString("linux_resolution", "1280x720")!!
    fun setLinuxResolution(c: Context, v: String) = prefs(c).edit().putString("linux_resolution", v).apply()

    fun freezeInBackground(c: Context) = prefs(c).getBoolean("freeze_background", true)
    fun setFreezeInBackground(c: Context, v: Boolean) = prefs(c).edit().putBoolean("freeze_background", v).apply()

    fun linuxSession(c: Context) = runCatching { Session.valueOf(prefs(c).getString("linux_session", null)!!) }.getOrDefault(Session.DESKTOP)
    fun setLinuxSession(c: Context, v: Session) = prefs(c).edit().putString("linux_session", v.name).apply()

    fun singleApp(c: Context) = prefs(c).getString("single_app", SINGLE_APPS.first())!!
    fun setSingleApp(c: Context, v: String) = prefs(c).edit().putString("single_app", v).apply()

    fun pip(c: Context) = prefs(c).getBoolean("pip", true)
    fun setPip(c: Context, v: Boolean) = prefs(c).edit().putBoolean("pip", v).apply()

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
