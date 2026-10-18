package com.spiros.app.compat

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import androidx.annotation.StringRes
import com.spiros.app.R
import com.spiros.app.vault.BiometricGate
import com.spiros.app.vault.crypto.KeystoreKeys
import java.io.File

/**
 * Runtime feature detection. SpirOS never branches on manufacturer or skin
 * (MagicOS, One UI, HyperOS, ColorOS, ...): it asks the system what it can do and
 * picks a fallback when a capability is missing.
 */
object DeviceCompat {

    enum class Level { OK, WARN, INFO }

    data class Check(
        @StringRes val title: Int,
        val level: Level,
        val detail: String,
        @StringRes val fallback: Int? = null,
    )

    data class Report(val checks: List<Check>, val canUseManagedProfile: Boolean)

    fun run(context: Context): Report {
        val pm = context.packageManager
        val checks = mutableListOf<Check>()

        checks += Check(
            R.string.compat_android,
            if (Build.VERSION.SDK_INT >= 34) Level.OK else Level.WARN,
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        checks += Check(
            R.string.compat_device,
            Level.INFO,
            "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DISPLAY}",
        )

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val managedUsers = pm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
        val provisioningAllowed = managedUsers &&
            runCatching { dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE) }
                .getOrDefault(false)
        val profiles = context.getSystemService(UserManager::class.java).userProfiles.size
        checks += Check(
            R.string.compat_work_profile,
            if (provisioningAllowed) Level.OK else Level.WARN,
            when {
                provisioningAllowed -> context.getString(R.string.compat_work_profile_ok)
                !managedUsers -> context.getString(R.string.compat_work_profile_missing)
                profiles > 1 -> context.getString(R.string.compat_work_profile_exists, profiles - 1)
                else -> context.getString(R.string.compat_work_profile_blocked)
            },
            if (provisioningAllowed) null else R.string.compat_work_profile_fallback,
        )

        val bio = BiometricGate.isAvailable(context)
        checks += Check(
            R.string.compat_biometric,
            if (bio) Level.OK else Level.WARN,
            context.getString(if (bio) R.string.compat_biometric_ok else R.string.compat_biometric_missing),
            if (bio) null else R.string.compat_biometric_fallback,
        )

        val level = runCatching { KeystoreKeys.deviceKeySecurityLevel() }.getOrNull()
        checks += Check(
            R.string.compat_keystore,
            when (level) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX, KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Level.OK
                else -> Level.WARN
            },
            when (level) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "Software"
                else -> "?"
            },
        )

        val kvm = File("/dev/kvm").let { it.exists() && it.canRead() }
        val avf = pm.hasSystemFeature("android.software.virtualization_framework")
        checks += Check(
            R.string.compat_virtualization,
            if (kvm) Level.OK else Level.INFO,
            "KVM: ${yesNo(context, kvm)} · AVF: ${yesNo(context, avf)}",
            if (kvm) null else R.string.compat_virtualization_fallback,
        )

        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val ramGb = mem.totalMem / (1024.0 * 1024 * 1024)
        val pageKb = Os.sysconf(OsConstants._SC_PAGESIZE) / 1024
        checks += Check(
            R.string.compat_hardware,
            if (ramGb >= 3.5) Level.OK else Level.WARN,
            "%s · %d cores · %.1f GB RAM · %d KB pages".format(
                Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
                Runtime.getRuntime().availableProcessors(),
                ramGb,
                pageKb,
            ),
        )

        return Report(checks, provisioningAllowed)
    }

    private fun yesNo(context: Context, value: Boolean) =
        context.getString(if (value) R.string.yes else R.string.no)
}
