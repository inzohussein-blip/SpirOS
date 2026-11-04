package com.spiros.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.remote.Engine

/** Human name of a task stage. */
@Composable
fun stageLabel(stage: String) = stringResource(
    when (stage) {
        "COPYING" -> R.string.task_stage_copy
        "VERIFYING" -> R.string.linux_stage_verify
        "EXTRACTING" -> R.string.linux_stage_extract
        "FINISHING" -> R.string.task_stage_finish
        "INSTALLING" -> R.string.task_stage_install
        else -> R.string.linux_stage_download
    },
)

private fun mb(bytes: Long) = if (bytes >= 1L shl 30) "%.2f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)

/**
 * Progress of the engine's background job with Pause / Resume / Cancel. The job keeps
 * running in the engine when this screen (or SpirOS) is closed; the control
 * notification shows it too.
 */
@Composable
fun TaskCard(task: Engine.Task, title: String, onRetry: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            TaskProgress(task, onRetry)
        }
    }
}

/** What part of a multi-part install is running ("Step 1 of 2: Windows base"). */
@Composable
fun stepLabel(task: Engine.Task): String? {
    if (task.steps <= 1 || task.step <= 0) return null
    val part = when (task.part) {
        "base" -> stringResource(R.string.task_part_base)
        "mt5" -> "MetaTrader 5"
        "mt4" -> "MetaTrader 4"
        else -> task.part
    }
    return stringResource(R.string.task_step, task.step, task.steps, part)
}

/**
 * Progress of a job without a card: stage, step, percentage, size, speed, time left,
 * Pause / Resume / Cancel, and the result when it ends. Used inside store items too.
 */
@Composable
fun TaskProgress(task: Engine.Task, onRetry: (() -> Unit)? = null, hint: Boolean = true) {
    val context = LocalContext.current
    // Download speed and time left, smoothed.
    var lastDone by remember { mutableLongStateOf(task.done) }
    var lastTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var speed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(task.done, task.stage) {
        val now = System.currentTimeMillis()
        val dt = (now - lastTime) / 1000f
        if (task.done < lastDone) speed = 0f
        else if (dt > 0.3f && !task.paused) {
            val s = (task.done - lastDone) / dt
            speed = if (speed == 0f) s else speed * 0.7f + s * 0.3f
        }
        lastDone = task.done
        lastTime = now
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        run {
            when {
                task.active -> {
                    stepLabel(task)?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    if (task.waiting == "wifi") {
                        Text(stringResource(R.string.task_waiting_wifi), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(
                        stageLabel(task.stage) + if (task.total > 0) "  ${(task.fraction * 100).toInt()}%" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (task.total > 0) {
                        LinearProgressIndicator(progress = { task.fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val details = buildList {
                        // apt reports percentages, not bytes.
                        val bytes = task.kind != "apt"
                        if (task.total > 0 && bytes) add("${mb(task.done)} / ${mb(task.total)}")
                        if (task.paused) add(context.getString(R.string.task_paused))
                        else if (bytes && speed > 1024 && task.total > 0) {
                            add("${mb(speed.toLong())}/s")
                            val left = ((task.total - task.done) / speed).toLong()
                            if (left > 0) add(context.getString(R.string.task_left, left / 60, left % 60))
                        }
                    }.joinToString(" · ")
                    if (details.isNotEmpty()) {
                        Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.paused) {
                            FilledTonalButton(onClick = { Engine.resumeTask(context) }) {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Text(stringResource(R.string.task_resume))
                            }
                        } else if (task.kind != "apt") { // apt is not paused half-way
                            FilledTonalButton(onClick = { Engine.pauseTask(context) }) {
                                Icon(Icons.Rounded.Pause, null)
                                Text(stringResource(R.string.task_pause))
                            }
                        }
                        OutlinedButton(onClick = { Engine.cancelTask(context) }) {
                            Icon(Icons.Rounded.Close, null)
                            Text(stringResource(R.string.cancel))
                        }
                    }
                    if (hint) Text(
                        stringResource(R.string.task_background_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                task.error == "cancelled" -> {
                    Text(stringResource(R.string.task_cancelled), style = MaterialTheme.typography.bodyMedium)
                    TaskEndButtons(onRetry, retryLabel = R.string.task_continue)
                }
                task.error != null -> {
                    Text(stringResource(R.string.task_failed, task.error), color = MaterialTheme.colorScheme.error)
                    TaskEndButtons(onRetry, retryLabel = R.string.task_retry)
                }
            }
        }
    }
}

@Composable
private fun TaskEndButtons(onRetry: (() -> Unit)?, retryLabel: Int) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onRetry != null) {
            FilledTonalButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, null)
                Text(stringResource(retryLabel))
            }
        }
        OutlinedButton(onClick = { Engine.clearTask(context) }) { Text(stringResource(R.string.task_dismiss)) }
    }
}
