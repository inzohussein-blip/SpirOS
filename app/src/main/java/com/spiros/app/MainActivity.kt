package com.spiros.app

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spiros.app.engine.LabSession
import com.spiros.app.perf.BackgroundFreezer
import com.spiros.app.perf.Performance
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.AppRoot
import com.spiros.app.ui.Disguise
import com.spiros.app.ui.ScreenSecurity
import com.spiros.app.ui.theme.SpirTheme

class MainActivity : ComponentActivity() {

    private val vault get() = (application as SpirApp).vault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or recents preview unless allowed in Settings.
        ScreenSecurity.apply(this)
        enableEdgeToEdge()
        if (Disguise.isEnabled(this)) {
            // The recents screen shows the task label; keep the disguise there too.
            val label = getString(R.string.disguise_label)
            setTaskDescription(
                if (Build.VERSION.SDK_INT >= 33) {
                    ActivityManager.TaskDescription.Builder().setLabel(label).build()
                } else {
                    @Suppress("DEPRECATION")
                    ActivityManager.TaskDescription(label)
                },
            )
        }
        vault.onDestroyed = { runOnUiThread(::destroySpace) }
        setContent {
            SpirTheme {
                AppRoot(vault)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Linux programs were frozen while SpirOS was in the background: wake them.
        Thread { BackgroundFreezer.resume() }.start()
    }

    override fun onResume() {
        super.onResume()
        // Back from a picker or the space: normal auto-lock applies again.
        vault.holdLock = false
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !vault.holdLock && Performance.freezeInBackground(this)) {
            Thread { BackgroundFreezer.freeze() }.start()
        }
        // Leaving SpirOS locks it, except while a system screen we opened is in front.
        // With the PIN lock turned off in Settings the session simply stays open.
        if (!isChangingConfigurations && !vault.holdLock && vault.isLockEnabled) {
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
