package com.spiros.app

import android.app.ActivityManager
import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spiros.app.engine.LabSession
import com.spiros.app.perf.BackgroundFreezer
import com.spiros.app.remote.Engine
import com.spiros.app.security.FlipToPanic
import com.spiros.app.perf.Performance
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.AppRoot
import com.spiros.app.ui.Disguise
import com.spiros.app.ui.Navigation
import com.spiros.app.ui.Pip
import com.spiros.app.ui.ScreenSecurity
import com.spiros.app.ui.theme.SpirTheme

class MainActivity : ComponentActivity() {

    private val vault get() = (application as SpirApp).vault
    private val flip by lazy { FlipToPanic(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, screen recording or recents preview unless allowed in Settings.
        ScreenSecurity.apply(this)
        Navigation.handle(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The control notification (Freeze / Stop / Panic) needs it on Android 13+.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Navigation.handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Linux programs were frozen while SpirOS was in the background: wake them.
        Thread { BackgroundFreezer.resume() }.start()
        Engine.send(this) { it.resume() }
    }

    override fun onResume() {
        super.onResume()
        flip.start()
        // Back from a picker or the space: normal auto-lock applies again.
        vault.holdLock = false
    }

    /** Pressing Home while the Linux desktop is open shrinks it to picture-in-picture. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Pip.desktopVisible && Performance.pip(this)) Pip.enter(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.setActive(isInPictureInPictureMode)
    }

    override fun onPause() {
        flip.stop()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !vault.holdLock && Performance.freezeInBackground(this)) {
            Thread { BackgroundFreezer.freeze() }.start()
            Engine.send(this) { it.freeze() }
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
            if (!space.sendDirect(this, SpaceOwner.CMD_DESTROY)) {
                space.command(SpaceOwner.CMD_DESTROY)?.let { runCatching { startActivity(it) } }
            }
        }
        space.forget()
    }
}
