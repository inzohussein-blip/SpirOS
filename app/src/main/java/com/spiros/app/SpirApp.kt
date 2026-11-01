package com.spiros.app

import android.app.Application
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.Shortcuts
import com.spiros.app.ui.theme.Appearance
import com.spiros.app.vault.Vault
import java.io.File

class SpirApp : Application() {
    // noBackupFilesDir: never part of any backup, even if backup rules change later.
    val vault: Vault by lazy { Vault(File(noBackupFilesDir, "vault")) }

    override fun onCreate() {
        super.onCreate()
        SpaceManager.configureComponents(this)
        Appearance.load(this)
        // Launcher shortcuts belong to the main profile's SpirOS, not the copy in the space.
        if (!SpaceOwner.isProfileOwner(this)) Shortcuts.update(this)
    }
}
