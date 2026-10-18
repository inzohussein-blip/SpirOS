package com.spiros.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spiros.app.engine.LabSession
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.AppRoot
import com.spiros.app.ui.theme.SpirTheme

class MainActivity : ComponentActivity() {

    private val vault get() = (application as SpirApp).vault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording, or previews in the recents screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        vault.onDestroyed = { runOnUiThread(::destroySpace) }
        setContent {
            SpirTheme {
                AppRoot(vault)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a picker or the space: normal auto-lock applies again.
        vault.holdLock = false
    }

    override fun onStop() {
        super.onStop()
        // Leaving SpirOS locks it, except while a system screen we opened is in front.
        if (!isChangingConfigurations && !vault.holdLock) {
            vault.lock()
            LabSession.stop()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) vault.onDestroyed = null
        super.onDestroy()
    }

    /** Erasing the vault (or the emergency PIN) also destroys the isolated space. */
    private fun destroySpace() {
        val space = SpaceManager(this)
        if (space.spaceUser() != null) {
            space.command(SpaceOwner.CMD_DESTROY)?.let { runCatching { startActivity(it) } }
        }
        space.forget()
    }
}
