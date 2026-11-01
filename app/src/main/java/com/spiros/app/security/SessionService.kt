package com.spiros.app.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.spiros.app.R
import com.spiros.app.engine.LabSession
import com.spiros.app.remote.Engine
import com.spiros.app.perf.BackgroundFreezer
import kotlin.concurrent.thread

/**
 * Foreground "control" notification while Linux, the AI or the Lab are running:
 * Open · Freeze/Resume · Stop · Panic. Being a foreground service also stops
 * Android from killing those programs while SpirOS is in the background.
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FREEZE -> thread {
                val freeze = !(BackgroundFreezer.frozen || Engine.status.value.frozen)
                if (freeze) BackgroundFreezer.freeze() else BackgroundFreezer.resume()
                Engine.send(this) { if (freeze) it.freeze() else it.resume() }
                Thread.sleep(300)
                post()
            }
            ACTION_STOP_ALL -> {
                BackgroundFreezer.resume()
                Engine.stopAll(this)
                LabSession.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PANIC -> {
                Panic.trigger(this)
                return START_NOT_STICKY
            }
        }
        post()
        return START_STICKY
    }

    private fun post() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
        val running = buildList {
            if (Engine.status.value.desktopRunning) add(getString(R.string.dock_linux))
            if (Engine.status.value.aiActive) add(getString(R.string.dock_ai))
            if (LabSession.running.value) add(getString(R.string.dock_lab))
        }.joinToString(" · ")
        val frozen = BackgroundFreezer.frozen || Engine.status.value.frozen
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setContentTitle(getString(if (frozen) R.string.notif_frozen else R.string.notif_running))
            .setContentText(running)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(open(this))
            .addAction(action(ACTION_FREEZE, if (frozen) R.string.notif_resume else R.string.notif_freeze, 1))
            .addAction(action(ACTION_STOP_ALL, R.string.notif_stop, 2))
            .addAction(action(ACTION_PANIC, R.string.panic, 3))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun action(name: String, label: Int, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, SessionService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(label), pi).build()
    }

    companion object {
        private const val CHANNEL = "spiros_sessions"
        private const val ID = 7
        private const val ACTION_FREEZE = "com.spiros.app.session.FREEZE"
        private const val ACTION_STOP_ALL = "com.spiros.app.session.STOP_ALL"
        private const val ACTION_PANIC = "com.spiros.app.session.PANIC"

        fun open(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(context.packageName, "com.spiros.app.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** Called whenever a session starts or stops; shows or removes the notification. */
        fun sync(context: Context) {
            val engine = Engine.status.value
            val anyRunning = engine.desktopRunning || engine.aiActive || LabSession.running.value
            val intent = Intent(context, SessionService::class.java)
            if (anyRunning) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }

        fun stop(context: Context) = context.stopService(Intent(context, SessionService::class.java))
    }
}
