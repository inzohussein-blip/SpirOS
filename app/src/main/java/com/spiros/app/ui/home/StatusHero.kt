package com.spiros.app.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.SpirApp
import com.spiros.app.remote.Engine
import com.spiros.app.remote.EngineInstaller
import com.spiros.app.ui.ScreenSecurity
import com.spiros.app.ui.common.TaskCard
import com.spiros.app.ui.linux.distroName

/** One protection SpirOS can check, with where to fix it. */
private data class Check(val ok: Boolean, val fix: Int, val action: () -> Unit)

/**
 * "Protected" / "N things need attention" at the top of Home, with the single most
 * useful action. Everything is checked locally.
 */
@Composable
fun StatusHero(
    shieldOn: Boolean,
    spaceReady: Boolean,
    onOpenShield: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val vault = (context.applicationContext as SpirApp).vault
    val checks = listOf(
        Check(shieldOn, R.string.hero_fix_shield, onOpenShield),
        Check(vault.isLockEnabled, R.string.hero_fix_lock, onOpenSettings),
        Check(!ScreenSecurity.screenshotsAllowed(context), R.string.hero_fix_screens, onOpenSettings),
        Check(spaceReady, R.string.hero_fix_space, onOpenSpace),
        Check(vault.hasPanicPin, R.string.hero_fix_panic, onOpenSettings),
    )
    val missing = checks.filterNot { it.ok }
    val good = missing.isEmpty()
    val scheme = MaterialTheme.colorScheme
    val accent = if (good) scheme.tertiary else scheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), scheme.surface.copy(alpha = 0.6f))))
            // A thin glowing edge in the status colour.
            .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.08f))), RoundedCornerShape(24.dp))
            .padding(18.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(accent.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (good) Icons.Rounded.GppGood else Icons.Rounded.GppMaybe, null, tint = accent, modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    stringResource(if (good) R.string.hero_protected else R.string.hero_attention, missing.size),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.hero_score, checks.size - missing.size, checks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        // Segments: one per protection.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            checks.forEach { c ->
                Box(
                    Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (c.ok) scheme.tertiary else scheme.onSurface.copy(alpha = 0.15f)),
                )
            }
        }
        missing.firstOrNull()?.let { next ->
            Button(onClick = next.action, modifier = Modifier.fillMaxWidth()) { Text(stringResource(next.fix)) }
        }
    }
}

/**
 * A small bar while something downloads or installs in the background (Linux system,
 * AI model, the engine itself); tapping it opens the task center.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskPill() {
    val status by Engine.status.collectAsState()
    val installer by EngineInstaller.state.collectAsState()
    val task = status.task?.takeIf { it.active || it.error != null }
    val engineBusy = installer is EngineInstaller.State.Downloading ||
        installer is EngineInstaller.State.Verifying || installer is EngineInstaller.State.Installing
    if (task == null && !engineBusy) return
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.secondaryContainer)
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (task?.active == true || engineBusy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Rounded.CloudDownload, null, tint = scheme.error)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(stringResource(R.string.tasks_title), style = MaterialTheme.typography.labelLarge)
            val line = when {
                task != null && task.active ->
                    taskTitle(task) + if (task.total > 0) " · ${(task.fraction * 100).toInt()}%" else ""
                task != null -> stringResource(R.string.tasks_needs_attention)
                else -> stringResource(R.string.engine_installing)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
            if (task?.active == true && task.total > 0) {
                LinearProgressIndicator(progress = { task.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.tasks_title), style = MaterialTheme.typography.titleLarge)
                if (task != null) TaskCard(task, title = taskTitle(task))
                if (engineBusy) {
                    Text(stringResource(R.string.engine_installing), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun taskTitle(task: Engine.Task) = when (task.kind) {
    "linux" -> stringResource(R.string.task_installing, distroName(task.name))
    "mt5" -> stringResource(R.string.mt5_installing)
    "apt" -> storeTaskTitle(task)
    else -> stringResource(R.string.ai_downloading_model, task.name.substringBefore(".gguf"))
}

/** "Installing VLC", "Removing GIMP", "Updating Linux apps" for app store tasks. */
@Composable
fun storeTaskTitle(task: Engine.Task): String {
    val action = task.name.substringBefore(' ')
    val what = task.name.substringAfter(' ', "").substringBefore(' ')
    return when (action) {
        "remove" -> stringResource(R.string.store_removing, what)
        "upgrade" -> stringResource(R.string.store_updating_all)
        else -> stringResource(R.string.store_installing, what)
    }
}
