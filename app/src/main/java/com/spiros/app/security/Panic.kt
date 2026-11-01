package com.spiros.app.security

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.spiros.app.SpirApp
import com.spiros.app.ai.AiServer
import com.spiros.app.engine.LabSession
import com.spiros.app.linux.LinuxSession
import com.spiros.app.ui.browser.burn

/**
 * Panic: in a fraction of a second stop everything, forget the vault key, erase the
 * browser, lock, drop SpirOS from Recents and show the phone's home screen.
 * Nothing is deleted from storage (that is the emergency PIN's job).
 */
object Panic {
    fun trigger(context: Context) {
        // Order matters: the key and the screen first, slower clean-up after.
        (context.applicationContext as SpirApp).vault.lock()
        // Close every SpirOS window and remove it from Recents, from wherever Panic came.
        (context as? Activity)?.finishAndRemoveTask()
        runCatching { context.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() } }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        LinuxSession.stop()
        AiServer.stop()
        LabSession.stop()
        runCatching { burn(context.applicationContext, null) }
        SessionService.stop(context)
    }
}
