package com.spiros.app.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.spiros.app.R
import com.spiros.app.ui.Navigation

/**
 * "MetaTrader 5 is ready – Open": one notification when a download / install in the
 * engine finishes (or fails), with a button that opens what was installed.
 */
object TaskNotifier {
    private const val CHANNEL = "spiros_downloads"
    private const val PREFS = "task_notifier"

    @Volatile private var lastSeen: Long = -1

    fun onStatus(context: Context, status: Engine.Status) {
        if (status.finished.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newest = status.finished.maxOf { it.id }
        if (lastSeen < 0) {
            // First run: start from now, never announce old jobs.
            if (!prefs.contains("seen")) { lastSeen = newest; prefs.edit().putLong("seen", newest).apply(); return }
            lastSeen = prefs.getLong("seen", 0)
        }
        // The engine restarted (ids start again): don't replay old jobs.
        if (newest < lastSeen) { lastSeen = newest; prefs.edit().putLong("seen", newest).apply(); return }
        status.finished.filter { it.id > lastSeen }.forEach { notify(context, it) }
        lastSeen = newest
        prefs.edit().putLong("seen", newest).apply()
    }

    private fun notify(context: Context, task: Engine.Task) {
        if (task.error == "cancelled") return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_downloads), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val (title, open) = when (task.kind) {
            "addon" -> {
                val name = if (task.name == "mt4") "MetaTrader 4" else "MetaTrader 5"
                name to Navigation.LINUX_APP + task.name
            }
            "apt" -> {
                val what = task.name.substringAfter(' ', "").substringBefore(' ')
                val app = LinuxStore.catalog(context).firstOrNull { it.main == what }
                (app?.name ?: what) to (app?.takeIf { it.exec.isNotBlank() }?.let { Navigation.LINUX_APP + "store:" + it.id } ?: Navigation.LINUX)
            }
            "linux" -> context.getString(R.string.dock_linux) to Navigation.LINUX
            else -> return
        }
        val ok = task.error == null
        val action = task.name.substringBefore(' ')
        val text = when {
            !ok -> context.getString(R.string.notif_task_failed, task.error)
            task.kind == "apt" && action == "remove" -> context.getString(R.string.notif_task_removed)
            else -> context.getString(R.string.notif_task_ready)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(context.packageName, "com.spiros.app.Launcher"))
            .putExtra(Navigation.EXTRA, if (ok && !(task.kind == "apt" && action == "remove")) open else Navigation.LINUX)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, task.id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .apply { if (ok && task.kind != "linux") addAction(Notification.Action.Builder(null, context.getString(R.string.store_open), pi).build()) }
            .build()
        runCatching { manager.notify(("task" + task.id).hashCode(), n) }
    }
}
