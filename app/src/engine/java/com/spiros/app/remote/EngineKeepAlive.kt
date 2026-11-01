package com.spiros.app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.spiros.app.R
import com.spiros.app.ai.AiServer
import com.spiros.app.linux.BackgroundTask
import com.spiros.app.linux.LinuxSession

/**
 * Keeps the engine process (and so Linux and the AI) alive while SpirOS is in the
 * background. SpirOS shows the real control notification; this one is minimal.
 */
class EngineKeepAlive : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.engine_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setContentTitle(getString(R.string.engine_running))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
        startForeground(ID, notification)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "spiros_engine"
        private const val ID = 9

        fun sync(context: Context) {
            val busy = LinuxSession.running.value || BackgroundTask.state?.active == true ||
                AiServer.state.value == AiServer.State.READY || AiServer.state.value == AiServer.State.LOADING
            val intent = Intent(context, EngineKeepAlive::class.java)
            if (busy) runCatching { context.startForegroundService(intent) } else context.stopService(intent)
        }
    }
}
