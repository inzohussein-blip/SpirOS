package com.spiros.app.shield

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.spiros.app.ui.Navigation

/** Quick Settings tile: turn the Shield (main phone) on or off without opening SpirOS. */
class ShieldTileService : TileService() {

    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (ShieldVpnService.running.value) {
            ShieldVpnService.stop(this)
            qsTile?.state = Tile.STATE_INACTIVE
            qsTile?.updateTile()
            return
        }
        if (VpnService.prepare(this) != null) {
            // Android has not granted the VPN yet: open the Shield screen to ask.
            openApp()
            return
        }
        ShieldVpnService.start(this)
        qsTile?.state = Tile.STATE_ACTIVE
        qsTile?.updateTile()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (ShieldVpnService.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(packageName, "com.spiros.app.Launcher"))
            .putExtra(Navigation.EXTRA, Navigation.SHIELD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
