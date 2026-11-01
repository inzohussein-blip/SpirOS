package com.spiros.app.security

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings "Panic" tile: one tap from anywhere. */
class PanicTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        Panic.trigger(this)
    }
}
