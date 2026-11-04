package com.spiros.app.perf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.spiros.app.remote.Engine

/**
 * Linux, the AI and MT5 run in the SpirOS Engine, in the background. Android (and
 * Honor / Huawei's own battery manager even more) slows such apps down; this asks,
 * once per app, to let SpirOS and the engine run without those limits.
 */
object BatteryExemption {
    fun spirosExempt(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun allExempt(context: Context) = spirosExempt(context) && (!Engine.status.value.connected || Engine.status.value.batteryExempt)

    @Suppress("BatteryLife")
    fun requestSpiros(context: Context) = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    fun requestEngine(context: Context) = runCatching {
        context.startActivity(
            Intent().setComponent(ComponentName(Engine.PACKAGE, "com.spiros.app.remote.EngineBatteryActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** Android's list of battery settings per app (for phones that need more, like Honor). */
    fun openSettings(context: Context) = runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    val isHonor get() = android.os.Build.MANUFACTURER.lowercase().let { it.contains("honor") || it.contains("huawei") }
}
