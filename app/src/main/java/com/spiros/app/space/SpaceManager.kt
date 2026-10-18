package com.spiros.app.space

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.PersistableBundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.graphics.drawable.toBitmap
import com.spiros.app.vault.crypto.Gcm

/**
 * Main-profile side of the isolated space. Reads the space through LauncherApps
 * (works for any app on any Android 14+ skin) and sends changes to
 * [SpaceCommandActivity] inside it, authenticated by a token shared at creation.
 */
class SpaceManager(private val context: Context) {

    data class App(
        val packageName: String,
        val label: String,
        val icon: Bitmap,
        val component: ComponentName,
        val frozen: Boolean,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)

    private val token: String? get() = prefs.getString(SpaceOwner.KEY_TOKEN, null)

    /** The profile holding our space, or null if none was created (or it was removed). */
    fun spaceUser(): UserHandle? {
        if (token == null) return null
        val me = Process.myUserHandle()
        return userManager.userProfiles.firstOrNull { user ->
            user != me && runCatching { launcherApps.getApplicationInfo(context.packageName, 0, user) }.isSuccess
        }
    }

    fun isPaused(user: UserHandle): Boolean = userManager.isQuietModeEnabled(user)

    /** Not every caller may toggle work-app pause; returns false if the system refused. */
    fun resume(user: UserHandle): Boolean =
        runCatching { userManager.requestQuietModeEnabled(false, user) }.getOrDefault(false)

    fun spaceApps(user: UserHandle): List<App> =
        launcherApps.getActivityList(null, user)
            .filter { it.applicationInfo.packageName != context.packageName }
            .map { it.toApp(badged = true) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

    /** Apps on the main profile that are not yet in the space. */
    fun cloneCandidates(user: UserHandle): List<App> {
        val inSpace = spaceApps(user).mapTo(HashSet()) { it.packageName }
        return launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != context.packageName }
            .filter { it.applicationInfo.packageName !in inSpace }
            .map { it.toApp(badged = false) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(app: App, user: UserHandle): Boolean =
        runCatching { launcherApps.startMainActivity(app.component, user, null, null) }.isSuccess

    fun provisioningIntent(): Intent? {
        val newToken = Gcm.randomBytes(32).joinToString("") { "%02x".format(it) }
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
            .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, SpaceOwner.admin(context))
            .putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle().apply { putString(SpaceOwner.KEY_TOKEN, newToken) },
            )
        if (intent.resolveActivity(context.packageManager) == null) return null
        prefs.edit().putString(SpaceOwner.KEY_TOKEN, newToken).apply()
        return intent
    }

    /**
     * Intent for a command inside the space, or null if it would not go straight to
     * the space (e.g. another app registered the same action and could read the token).
     */
    fun command(cmd: String, packages: List<String> = emptyList()): Intent? {
        val t = token ?: return null
        val intent = Intent(SpaceOwner.ACTION_COMMAND)
            .putExtra(SpaceOwner.EXTRA_TOKEN, t)
            .putExtra(SpaceOwner.EXTRA_COMMAND, cmd)
            .putStringArrayListExtra(SpaceOwner.EXTRA_PACKAGES, ArrayList(packages))
        val targets = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (targets.isEmpty() || targets.any { !it.isCrossProfileIntentForwarderActivity }) return null
        return intent
    }

    /** Forget the space (after it was destroyed). */
    fun forget() = prefs.edit().clear().apply()

    private fun LauncherActivityInfo.toApp(badged: Boolean) = App(
        packageName = applicationInfo.packageName,
        label = label.toString(),
        icon = (if (badged) getBadgedIcon(0) else getIcon(0)).toBitmap(ICON_PX, ICON_PX),
        component = componentName,
        frozen = (applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
    )

    companion object {
        private const val PREFS = "space"
        private const val ICON_PX = 128

        /** The copy of SpirOS inside the space must not answer commands meant for it from here. */
        fun configureComponents(context: Context) {
            val inSpace = SpaceOwner.isProfileOwner(context)
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, SpaceCommandActivity::class.java),
                if (inSpace) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
