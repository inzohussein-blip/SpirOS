package com.spiros.app.space

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * If the system refuses a silent uninstall inside the space, hide the app instead
 * so it still disappears from the space (its data stays isolated there).
 */
class UninstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        runCatching {
            context.getSystemService(DevicePolicyManager::class.java)
                .setApplicationHidden(SpaceOwner.admin(context), pkg, true)
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "com.spiros.app.extra.UNINSTALL_PACKAGE"
    }
}
