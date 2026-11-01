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
import android.os.Build
import android.os.Bundle
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

    // ---- other profiles (not ours) ------------------------------------------------------

    enum class ProfileType { WORK, CLONE, PRIVATE, UNKNOWN }

    /** A profile on this phone that SpirOS does not own (e.g. Shelter, Island, a company, app twins). */
    data class OtherProfile(
        val user: UserHandle,
        val type: ProfileType,
        val paused: Boolean,
        /** Display name of the app that most likely manages it, if recognised. */
        val owner: String?,
        val ownerPackage: String?,
        /** Left behind by an earlier SpirOS install whose pairing key was lost. */
        val isOldSpirOS: Boolean,
        val apps: List<App>,
    )

    /**
     * Android lets only a profile's own admin app (or the user, in Settings) delete or
     * manage it. SpirOS can still identify it, list and open its apps, and pause it
     * where the system allows.
     */
    fun otherProfiles(): List<OtherProfile> {
        val me = Process.myUserHandle()
        val ours = spaceUser()
        return userManager.userProfiles.filter { it != me && it != ours }.map { user ->
            val owner = KNOWN_ADMINS.entries.firstOrNull { (pkg, _) -> isInstalled(pkg, user) }
            OtherProfile(
                user = user,
                type = profileType(user),
                paused = runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(false),
                owner = owner?.value,
                ownerPackage = owner?.key,
                isOldSpirOS = isInstalled(context.packageName, user),
                apps = runCatching { spaceApps(user) }.getOrDefault(emptyList()),
            )
        }
    }

    fun setPaused(user: UserHandle, paused: Boolean): Boolean =
        runCatching { userManager.requestQuietModeEnabled(paused, user) }.getOrDefault(false)

    private fun isInstalled(pkg: String, user: UserHandle) =
        runCatching { launcherApps.getApplicationInfo(pkg, 0, user) }.getOrNull() != null

    private fun profileType(user: UserHandle): ProfileType {
        if (Build.VERSION.SDK_INT < 35) return ProfileType.UNKNOWN
        return when (runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull()) {
            UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileType.WORK
            UserManager.USER_TYPE_PROFILE_CLONE -> ProfileType.CLONE
            UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileType.PRIVATE
            else -> ProfileType.UNKNOWN
        }
    }

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
     * the space (e.g. another app registered the same action and could read the token);
     * [unreachableReason] then says why.
     */
    fun command(cmd: String, packages: List<String> = emptyList(), extras: Bundle? = null): Intent? {
        val intent = commandIntent(cmd, packages, extras) ?: return null
        val targets = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        // Only the system's cross-profile forwarder may receive it (some skins ship their
        // own forwarder that does not carry the AOSP flag, but it is always a system app).
        val safe = targets.isNotEmpty() && targets.all { r ->
            r.isCrossProfileIntentForwarderActivity ||
                (r.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }
        return if (safe) intent else null
    }

    private fun commandIntent(cmd: String, packages: List<String>, extras: Bundle?): Intent? {
        val t = token ?: return null
        return Intent(SpaceOwner.ACTION_COMMAND)
            .apply { if (extras != null) putExtras(extras) }
            .putExtra(SpaceOwner.EXTRA_TOKEN, t)
            .putExtra(SpaceOwner.EXTRA_COMMAND, cmd)
            .putStringArrayListExtra(SpaceOwner.EXTRA_PACKAGES, ArrayList(packages))
    }

    // ---- direct channel: Android's "connected apps" across profiles ----------------------
    // The space allows it (Profile Owner, setCrossProfilePackages) and the user approves it
    // once in system settings. Then SpirOS starts its own command activity inside the space
    // directly, without the system's intent forwarder that some skins break.

    private val crossProfile get() = context.getSystemService(android.content.pm.CrossProfileApps::class.java)

    /** The direct channel is approved and the space is there. */
    fun directReady(): Boolean = runCatching { crossProfile.canInteractAcrossProfiles() }.getOrDefault(false) && spaceUser() != null

    /** System screen where the user approves the direct channel, or null if not offered (yet). */
    fun connectIntent(): Intent? = runCatching {
        val cp = crossProfile
        if (!cp.canInteractAcrossProfiles() && cp.canRequestInteractAcrossProfiles()) cp.createRequestInteractAcrossProfilesIntent() else null
    }.getOrNull()

    /** Sends a command straight to SpirOS in the space; false if the direct channel is not available. */
    fun sendDirect(activity: android.app.Activity, cmd: String, packages: List<String> = emptyList(), extras: Bundle? = null): Boolean {
        if (!runCatching { crossProfile.canInteractAcrossProfiles() }.getOrDefault(false)) return false
        val user = spaceUser() ?: return false
        val intent = commandIntent(cmd, packages, extras)?.setComponent(ComponentName(context, SpaceCommandActivity::class.java))
            ?: return false
        return runCatching { crossProfile.startActivity(intent, user, activity) }.isSuccess
    }

    /**
     * Starts SpirOS inside the space so it can finish its own setup (some skins never
     * deliver the provisioning-complete signal). False if its icon there is gone.
     */
    fun repair(): Boolean {
        val user = spaceUser() ?: return false
        val own = runCatching { launcherApps.getActivityList(context.packageName, user) }.getOrNull()?.firstOrNull() ?: return false
        return runCatching { launcherApps.startMainActivity(own.componentName, user, null, null) }.isSuccess
    }

    /** Neither channel exists: SpirOS in the space never finished its setup. */
    fun needsRepair(): Boolean {
        if (spaceUser() == null || directReady() || connectIntent() != null) return false
        val targets = context.packageManager.queryIntentActivities(Intent(SpaceOwner.ACTION_COMMAND), PackageManager.MATCH_DEFAULT_ONLY)
        return targets.isEmpty()
    }

    /** Human-readable connection report for the diagnostics card. */
    fun diagnostics(): List<Pair<String, String>> {
        val user = spaceUser()
        val probe = Intent(SpaceOwner.ACTION_COMMAND)
        val targets = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        val cp = runCatching { crossProfile }.getOrNull()
        return listOf(
            "pairing key" to if (token != null) "yes" else "NO",
            "space profile" to (user?.toString() ?: "not found"),
            "paused" to (user?.let { isPaused(it).toString() } ?: "-"),
            "direct channel" to when {
                cp == null -> "unavailable"
                runCatching { cp.canInteractAcrossProfiles() }.getOrDefault(false) -> "approved"
                runCatching { cp.canRequestInteractAcrossProfiles() }.getOrDefault(false) -> "can be approved"
                else -> "not offered by the space yet"
            },
            "forwarders" to if (targets.isEmpty()) "none" else targets.joinToString { r ->
                r.activityInfo.packageName.substringAfterLast('.') + "/" + r.activityInfo.name.substringAfterLast('.') +
                    if (r.isCrossProfileIntentForwarderActivity) "*" else ""
            },
            "other profiles" to (userManager.userProfiles.size - 1 - (if (user != null) 1 else 0)).toString(),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
        )
    }

    /** Why [command] returned null, as a message for the user. */
    fun unreachableReason(): Int {
        val user = spaceUser() ?: return com.spiros.app.R.string.space_unreachable_none
        if (isPaused(user)) return com.spiros.app.R.string.space_unreachable_paused
        val probe = Intent(SpaceOwner.ACTION_COMMAND)
        val targets = context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return if (targets.isEmpty()) com.spiros.app.R.string.space_unreachable_channel
        else com.spiros.app.R.string.space_unreachable_unsafe
    }

    /** Forget the space (after it was destroyed). */
    fun forget() = prefs.edit().clear().apply()

    private fun LauncherActivityInfo.toApp(badged: Boolean) = App(
        packageName = applicationInfo.packageName,
        label = label.toString(),
        icon = cachedIcon(this, badged),
        component = componentName,
        frozen = (applicationInfo.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
    )

    /** Turning drawables into bitmaps is the slow part of listing apps; do it once. */
    private fun cachedIcon(info: LauncherActivityInfo, badged: Boolean): Bitmap {
        val key = "${info.user.hashCode()}/${info.componentName.flattenToShortString()}/$badged/${info.applicationInfo.sourceDir}"
        ICONS.get(key)?.let { return it }
        return (if (badged) info.getBadgedIcon(0) else info.getIcon(0)).toBitmap(ICON_PX, ICON_PX).also { ICONS.put(key, it) }
    }

    companion object {
        private val ICONS = android.util.LruCache<String, Bitmap>(256)
        private const val PREFS = "space"
        private const val ICON_PX = 128

        /** Admin apps that commonly create work profiles (also listed in <queries>). */
        val KNOWN_ADMINS = linkedMapOf(
            "com.spiros.app" to "SpirOS",
            "net.typeblog.shelter" to "Shelter",
            "com.oasisfeng.island" to "Island",
            "com.oasisfeng.island.fdroid" to "Insular",
            "com.google.android.apps.work.clouddpc" to "Android Device Policy",
            "com.microsoft.windowsintune.companyportal" to "Intune Company Portal",
            "com.airwatch.androidagent" to "Workspace ONE",
            "com.mobileiron" to "MobileIron",
            "com.samsung.android.knox.containercore" to "Knox",
        )

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
