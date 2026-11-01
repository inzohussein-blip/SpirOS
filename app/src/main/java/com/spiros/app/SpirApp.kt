package com.spiros.app

import android.app.Application
import com.spiros.app.space.SpaceManager
import com.spiros.app.engine.LabSession
import com.spiros.app.remote.Engine
import com.spiros.app.security.SessionService
import com.spiros.app.space.SpaceOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
        // Inside the space: make sure the main profile can still reach us.
        if (SpaceOwner.isProfileOwner(this)) Thread { SpaceOwner.ensureSetup(this) }.start()
        Appearance.load(this)
        // Launcher shortcuts belong to the main profile's SpirOS, not the copy in the space.
        if (!SpaceOwner.isProfileOwner(this)) Shortcuts.update(this)
        // Linux and the AI run in the SpirOS Engine, if it is installed (never in the space).
        if (!SpaceOwner.isProfileOwner(this)) Thread { Engine.connect(this) }.start()
        // Show or remove the control notification as Linux, the AI or the Lab start and stop.
        MainScope().launch {
            // Also follows a background install/download (progress, paused) for the notification.
            combine(Engine.status, LabSession.running) { engine, lab ->
                val t = engine.task?.takeIf { it.active }
                listOf(engine.desktopRunning, engine.aiState, lab, t?.paused, t?.let { (it.fraction * 100).toInt() }, t?.stage)
            }.distinctUntilChanged().collect { SessionService.sync(this@SpirApp) }
        }
    }
}
