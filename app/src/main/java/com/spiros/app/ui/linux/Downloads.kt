package com.spiros.app.ui.linux

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.perf.Performance
import com.spiros.app.remote.Engine
import com.spiros.app.remote.LinuxApps
import com.spiros.app.remote.LinuxStore
import com.spiros.app.ui.common.TaskProgress
import com.spiros.app.ui.common.stepLabel

fun megabytes(bytes: Long) = if (bytes >= 1L shl 30) "%.2f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)

/** The engine job that installs [app], if one is running (or [queued] behind it). */
fun appTask(status: Engine.Status, app: LinuxApps.App): Engine.Task? = status.task?.takeIf { t ->
    t.active && when {
        app.id in LinuxApps.ADDONS -> t.kind == "addon" && t.name == app.id
        app.id.startsWith("store:") -> t.kind == "apt" && t.name.startsWith("install ") && app.packages?.let { t.name == "install $it" } == true
        else -> false
    }
}

fun appQueued(status: Engine.Status, app: LinuxApps.App) = when {
    app.id in LinuxApps.ADDONS -> ("addon" to app.id) in status.queue
    app.id.startsWith("store:") -> app.packages?.let { ("apt" to "install $it") in status.queue } == true
    else -> false
}

/**
 * A Linux app's icon with its state: a progress ring around it while it downloads
 * (with the percentage), a clock while it waits in the queue, a dot when an update
 * is available.
 */
@Composable
fun LinuxAppIcon(app: LinuxApps.App, size: Dp, update: Boolean = false) {
    val status by Engine.status.collectAsState()
    val task = appTask(status, app)
    val queued = task == null && appQueued(status, app)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size + 10.dp)) {
        Image(painterResource(app.icon), null, Modifier.size(if (task != null || queued) size - 8.dp else size))
        when {
            task != null && task.total > 0 -> CircularProgressIndicator(
                progress = { task.fraction }, strokeWidth = 4.dp, modifier = Modifier.size(size + 8.dp),
            )
            task != null -> CircularProgressIndicator(strokeWidth = 4.dp, modifier = Modifier.size(size + 8.dp))
            queued -> Icon(
                Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(2.dp),
            )
        }
        if (update && task == null) {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        }
    }
}

/** Label under an app icon: its name, or the download's percentage / step. */
@Composable
fun linuxAppLabel(app: LinuxApps.App): String {
    val context = LocalContext.current
    val status by Engine.status.collectAsState()
    val task = appTask(status, app)
    return when {
        task == null && appQueued(status, app) -> stringResource(R.string.download_queued)
        task == null -> app.name(context)
        task.waiting == "wifi" -> stringResource(R.string.download_wifi_short)
        task.total > 0 -> "${(task.fraction * 100).toInt()}%" + (if (task.steps > 1) " · ${task.step}/${task.steps}" else "")
        else -> app.name(context)
    }
}

/**
 * Asked before a big download: size, free space (warning if short), "only on Wi-Fi".
 * Lives once in the app; anything calls LinuxStore.ask(...).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadConfirmHost() {
    val context = LocalContext.current
    val request by LinuxStore.confirm.collectAsState()
    val c = request ?: return
    val status by Engine.status.collectAsState()
    var wifiOnly by remember { mutableStateOf(Performance.wifiOnly(context)) }
    LaunchedEffect(Unit) { LinuxStore.refreshPublished() }
    ModalBottomSheet(onDismissRequest = { LinuxStore.ask(null) }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.download_title, c.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
            }
            if (c.bytes > 0) {
                Text(stringResource(R.string.download_size, megabytes(c.bytes)), style = MaterialTheme.typography.bodyLarge)
            }
            if (status.freeBytes > 0) {
                // Unpacked, programs take about twice their download.
                val short = c.bytes > 0 && status.freeBytes < c.bytes * 3
                Text(
                    stringResource(if (short) R.string.download_free_short else R.string.download_free, megabytes(status.freeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (short) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.download_wifi_only), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.download_wifi_only_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = wifiOnly, onCheckedChange = {
                    wifiOnly = it
                    Performance.setWifiOnly(context, it)
                    Engine.setWifiOnly(context, it)
                })
            }
            Text(stringResource(R.string.download_background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.start(); LinuxStore.ask(null) }) { Text(stringResource(R.string.download_start)) }
                OutlinedButton(onClick = { LinuxStore.ask(null) }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/** Downloads tab of the store: the running job with full progress, then the queue. */
@Composable
fun DownloadsList(onEmpty: @Composable () -> Unit) {
    val context = LocalContext.current
    val status by Engine.status.collectAsState()
    var wifiOnly by remember { mutableStateOf(Performance.wifiOnly(context)) }
    val task = status.task?.takeIf { it.kind == "apt" || it.kind == "addon" || it.kind == "linux" || it.kind == "model" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.download_wifi_only), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = wifiOnly, onCheckedChange = {
                wifiOnly = it
                Performance.setWifiOnly(context, it)
                Engine.setWifiOnly(context, it)
            })
        }
        if (task == null && status.queue.isEmpty()) onEmpty()
        task?.takeIf { it.active || it.error != null }?.let { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(downloadTitle(t.kind, t.name), style = MaterialTheme.typography.titleSmall)
                    TaskProgress(t, hint = false)
                }
            }
        }
        status.queue.forEach { (kind, name) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(downloadTitle(kind, name), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.download_queued), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { Engine.dequeue(context, kind, name) }) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
fun downloadTitle(kind: String, name: String): String {
    val context = LocalContext.current
    return when (kind) {
        "addon" -> if (name == "mt4") "MetaTrader 4" else "MetaTrader 5"
        "apt" -> {
            val action = name.substringBefore(' ')
            val pkgs = name.substringAfter(' ', "")
            val app = LinuxStore.catalog(context).firstOrNull { it.packages == pkgs }?.name ?: pkgs.substringBefore(' ')
            when (action) {
                "remove" -> stringResource(R.string.store_removing, app)
                "upgrade" -> stringResource(R.string.store_updating_all)
                else -> stringResource(R.string.store_installing, app)
            }
        }
        "linux" -> stringResource(R.string.task_installing, distroName(name))
        else -> stringResource(R.string.ai_downloading_model, name.substringBefore(".gguf"))
    }
}

/** Step + percentage in one line, e.g. for a store item while it downloads. */
@Composable
fun shortProgress(task: Engine.Task): String =
    listOfNotNull(stepLabel(task), if (task.total > 0) "${(task.fraction * 100).toInt()}%" else null).joinToString(" · ")
