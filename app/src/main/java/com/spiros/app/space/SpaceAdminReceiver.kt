package com.spiros.app.space

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.IntentCompat

class SpaceAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        SpaceOwner.finalize(
            context,
            IntentCompat.getParcelableExtra(intent, DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java),
        )
    }
}

/** Repairs the space's command channel after a SpirOS update or a restart (space side). */
class SpaceRepairReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SpaceOwner.ensureSetup(context)
    }
}
