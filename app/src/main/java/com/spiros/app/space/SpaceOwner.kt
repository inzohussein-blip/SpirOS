package com.spiros.app.space

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.os.UserManager
import com.spiros.app.shield.ShieldSettings
import com.spiros.app.shield.ShieldVpnService
import java.security.MessageDigest

/**
 * Code that runs inside the isolated space, where SpirOS is the Profile Owner.
 * The main-profile SpirOS drives it through [SpaceCommandActivity].
 */
object SpaceOwner {
    const val ACTION_COMMAND = "com.spiros.app.action.SPACE_COMMAND"
    const val EXTRA_TOKEN = "com.spiros.app.extra.TOKEN"
    const val EXTRA_COMMAND = "com.spiros.app.extra.COMMAND"
    const val EXTRA_PACKAGES = "com.spiros.app.extra.PACKAGES"
    const val KEY_TOKEN = "token"

    const val CMD_CLONE = "clone"
    const val CMD_REMOVE = "remove"
    const val CMD_FREEZE = "freeze"
    const val CMD_UNFREEZE = "unfreeze"
    const val CMD_FREEZE_ALL = "freeze_all"
    const val CMD_UNFREEZE_ALL = "unfreeze_all"
    const val CMD_LAUNCH = "launch"
    const val CMD_DESTROY = "destroy"
    const val CMD_SHIELD_ON = "shield_on"
    const val CMD_SHIELD_OFF = "shield_off"
    const val CMD_GOOGLE_OFF = "google_off"
    const val CMD_GOOGLE_ON = "google_on"

    const val EXTRA_UPSTREAM = "com.spiros.app.extra.UPSTREAM"
    const val EXTRA_FULL_LIST = "com.spiros.app.extra.FULL_LIST"

    /** Google services hidden inside the space when the user opts out of them. */
    private val GOOGLE_PACKAGES = listOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
    )

    private const val PREFS = "space_owner"
    private const val LAUNCHER_ALIAS = "com.spiros.app.Launcher"

    fun admin(context: Context) = ComponentName(context, SpaceAdminReceiver::class.java)

    fun isProfileOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isProfileOwnerApp(context.packageName)

    /** Idempotent: called from both the provisioning broadcast and the compliance activity. */
    fun finalize(context: Context, extras: PersistableBundle?) {
        if (!isProfileOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = admin(context)

        extras?.getString(KEY_TOKEN)?.let { prefs(context).edit().putString(KEY_TOKEN, it).apply() }

        runCatching { dpm.setProfileName(admin, "SpirOS") }
        ensureSetup(context)
        runCatching { dpm.setProfileEnabled(admin) }
    }

    /**
     * (Re)applies what the main profile needs to reach the space. Idempotent; runs at
     * provisioning and again whenever SpirOS starts in the space (after an update, a
     * phone restart or the space being resumed), so a channel lost on some skins
     * repairs itself.
     */
    fun ensureSetup(context: Context) {
        if (!isProfileOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = admin(context)
        // Let the main profile reach SpaceCommandActivity in here.
        runCatching {
            dpm.clearCrossProfileIntentFilters(admin)
            val filter = IntentFilter(ACTION_COMMAND).apply { addCategory(Intent.CATEGORY_DEFAULT) }
            dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        }
        // Second, direct channel: allow SpirOS (the same app in the main profile) to start
        // its command activity in here once the user approves "connected apps".
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { dpm.setCrossProfilePackages(admin, setOf(context.packageName)) }
        }
        // Nothing copied in the space can be pasted outside, and vice versa.
        runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE) }
        // The SpirOS copy in the space has no UI of its own; hide its launcher icon.
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, LAUNCHER_ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    fun isAuthorized(context: Context, intent: Intent): Boolean {
        val expected = prefs(context).getString(KEY_TOKEN, null) ?: return false
        val given = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    fun execute(context: Context, intent: Intent): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = admin(context)
        val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
            .filter { it != context.packageName }

        return when (intent.getStringExtra(EXTRA_COMMAND)) {
            CMD_CLONE -> packages.map { pkg ->
                runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                runCatching { dpm.installExistingPackage(admin, pkg) }.getOrDefault(false)
            }.all { it }
            CMD_REMOVE -> {
                packages.forEach { requestUninstall(context, it) }
                true
            }
            CMD_FREEZE -> dpm.setPackagesSuspended(admin, packages.toTypedArray(), true).isEmpty()
            CMD_UNFREEZE -> dpm.setPackagesSuspended(admin, packages.toTypedArray(), false).isEmpty()
            CMD_FREEZE_ALL -> dpm.setPackagesSuspended(admin, launchablePackages(context), true).isEmpty()
            CMD_UNFREEZE_ALL -> dpm.setPackagesSuspended(admin, launchablePackages(context), false).isEmpty()
            CMD_LAUNCH -> {
                val launch = packages.firstOrNull()?.let(context.packageManager::getLaunchIntentForPackage)
                launch?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } != null
            }
            CMD_SHIELD_ON -> {
                intent.getStringExtra(EXTRA_UPSTREAM)?.let { name ->
                    runCatching { ShieldSettings.setUpstream(context, ShieldSettings.Upstream.valueOf(name)) }
                }
                ShieldSettings.setUseFullList(context, intent.getBooleanExtra(EXTRA_FULL_LIST, false))
                // As Profile Owner we can make the Shield the space's always-on VPN:
                // no consent prompt, and it restarts with the profile.
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
                ShieldVpnService.stop(context)
                ShieldVpnService.start(context)
                true
            }
            CMD_SHIELD_OFF -> {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
                ShieldVpnService.stop(context)
                true
            }
            CMD_GOOGLE_OFF -> GOOGLE_PACKAGES.map { pkg ->
                runCatching { dpm.setApplicationHidden(admin, pkg, true) }.isSuccess
            }.all { it }
            CMD_GOOGLE_ON -> GOOGLE_PACKAGES.map { pkg ->
                runCatching { dpm.setApplicationHidden(admin, pkg, false) }.isSuccess
            }.all { it }
            CMD_DESTROY -> {
                @Suppress("DEPRECATION")
                dpm.wipeData(0)
                true
            }
            else -> false
        }
    }

    private fun launchablePackages(context: Context): Array<String> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
            .toTypedArray()
    }

    private fun requestUninstall(context: Context, pkg: String) {
        val callback = PendingIntent.getBroadcast(
            context,
            pkg.hashCode(),
            Intent(context, UninstallResultReceiver::class.java).putExtra(UninstallResultReceiver.EXTRA_PACKAGE, pkg),
            // The installer fills in the result extras, so the PendingIntent must be mutable.
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.packageManager.packageInstaller.uninstall(pkg, callback.intentSender)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
