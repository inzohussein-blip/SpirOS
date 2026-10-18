package com.spiros.app.space

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.PersistableBundle
import android.os.UserManager
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

        dpm.setProfileName(admin, "SpirOS")
        // Let the main profile reach SpaceCommandActivity in here.
        val filter = IntentFilter(ACTION_COMMAND).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        dpm.addCrossProfileIntentFilter(admin, filter, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        // Nothing copied in the space can be pasted outside, and vice versa.
        dpm.addUserRestriction(admin, UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE)
        // The SpirOS copy in the space has no UI of its own; hide its launcher icon.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, LAUNCHER_ALIAS),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        dpm.setProfileEnabled(admin)
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
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.packageManager.packageInstaller.uninstall(pkg, callback.intentSender)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
