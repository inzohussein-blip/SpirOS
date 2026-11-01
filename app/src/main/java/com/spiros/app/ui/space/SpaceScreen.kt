package com.spiros.app.ui.space

import android.app.Activity
import android.os.UserHandle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spiros.app.R
import com.spiros.app.compat.DeviceCompat
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface SpaceState {
    data class Unsupported(val reason: String, val others: List<SpaceManager.OtherProfile>) : SpaceState
    data class Absent(val others: List<SpaceManager.OtherProfile>) : SpaceState
    data class Paused(val user: UserHandle) : SpaceState
    data class Active(val user: UserHandle, val apps: List<SpaceManager.App>) : SpaceState
}

@Composable
fun SpaceScreen(vault: Vault, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val space = remember { SpaceManager(context) }
    val snackbar = remember { SnackbarHostState() }
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<SpaceState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var lastCommand by remember { mutableStateOf("") }
    /** A failed delete: explain and offer the system settings. */
    var problem by remember { mutableStateOf<Int?>(null) }
    /** Just provisioned: the new profile finishes setting itself up for a few seconds. */
    var creating by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) {
        state = withContext(Dispatchers.IO) { loadState(context, space) }
    }

    fun message(res: Int) = scope.launch { snackbar.showSnackbar(context.getString(res)) }

    val provisioning = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vault.holdLock = false
        busy = false
        if (result.resultCode != Activity.RESULT_OK) message(R.string.space_create_failed)
        // The new profile finishes setting itself up in the background (some skins take
        // a while): show "creating" instead of the state of the half-made profile.
        creating = true
        scope.launch {
            repeat(40) {
                refresh++
                delay(1500)
                if (state is SpaceState.Active || state is SpaceState.Paused) {
                    creating = false
                    return@launch
                }
            }
            creating = false
            refresh++
        }
    }
    val commands = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vault.holdLock = false
        busy = false
        if (result.resultCode != Activity.RESULT_OK) {
            if (lastCommand == SpaceOwner.CMD_DESTROY) problem = R.string.space_command_refused else message(R.string.space_command_refused)
        }
        refresh++
    }

    fun send(cmd: String, packages: List<String> = emptyList()) {
        lastCommand = cmd
        // Direct channel first (works on every skin once approved), then the forwarder.
        val activity = context.findActivity()
        if (activity != null && space.sendDirect(activity, cmd, packages)) {
            scope.launch { repeat(3) { delay(1200); refresh++ } }
            return
        }
        val intent = space.command(cmd, packages)
        if (intent == null) {
            // Deleting must always be possible: offer the system's own removal instead.
            if (cmd == SpaceOwner.CMD_DESTROY) problem = space.unreachableReason() else message(space.unreachableReason())
            return
        }
        vault.holdLock = true
        busy = true
        runCatching { commands.launch(intent) }.onFailure {
            vault.holdLock = false
            busy = false
            message(R.string.space_unreachable)
        }
    }

    val current = state
    if (adding && current is SpaceState.Active) {
        AddAppsScreen(
            loadCandidates = { space.cloneCandidates(current.user) },
            onBack = { adding = false },
            onConfirm = { pkgs ->
                adding = false
                send(SpaceOwner.CMD_CLONE, pkgs)
            },
        )
        return
    }

    Scaffold(
        topBar = {
            SpirTopBar(stringResource(R.string.section_space), onBack) {
                if (current is SpaceState.Active) {
                    IconButton(onClick = { send(SpaceOwner.CMD_FREEZE_ALL) }) {
                        Icon(Icons.Rounded.AcUnit, contentDescription = stringResource(R.string.space_freeze_all))
                    }
                    IconButton(onClick = { send(SpaceOwner.CMD_UNFREEZE_ALL) }) {
                        Icon(Icons.Rounded.WbSunny, contentDescription = stringResource(R.string.space_unfreeze_all))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Rounded.DeleteForever,
                            contentDescription = stringResource(R.string.space_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (current is SpaceState.Active && !busy) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.space_add_apps)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                creating && current !is SpaceState.Active && current !is SpaceState.Paused -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.space_creating), textAlign = TextAlign.Center)
                }
                else -> Unit
            }
            if (current is SpaceState.Active || current is SpaceState.Paused) {
                SpaceConnection(space, refresh, onLeaveApp = { vault.holdLock = true })
            }
            if (!creating || current is SpaceState.Active || current is SpaceState.Paused) when (current) {
                null -> androidx.compose.foundation.layout.Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Placeholders shaped like the app grid while the space is read.
                    repeat(3) {
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            repeat(4) { com.spiros.app.ui.common.Skeleton(Modifier.size(64.dp), corner = 16.dp) }
                        }
                    }
                }
                is SpaceState.Unsupported -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.space_unavailable) + "\n" + current.reason,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (current.others.isNotEmpty()) {
                        Text(stringResource(R.string.other_explainer), style = MaterialTheme.typography.bodySmall)
                        current.others.forEach { profile ->
                            OtherProfileCard(
                                profile = profile,
                                space = space,
                                onLeaveApp = { vault.holdLock = true },
                                onChanged = { refresh++ },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.compat_work_profile_fallback),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                is SpaceState.Absent -> Centered {
                    Text(stringResource(R.string.space_intro), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            val intent = space.provisioningIntent()
                            if (intent == null) {
                                message(R.string.space_create_failed)
                            } else {
                                vault.holdLock = true
                                busy = true
                                provisioning.launch(intent)
                            }
                        },
                    ) { Text(stringResource(R.string.space_create)) }
                }
                is SpaceState.Paused -> Centered {
                    Text(stringResource(R.string.space_paused), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        if (!space.resume(current.user)) message(R.string.space_resume_manual)
                        refresh++
                    }) { Text(stringResource(R.string.space_resume)) }
                }
                is SpaceState.Active -> if (current.apps.isEmpty()) {
                    com.spiros.app.ui.common.EmptyState(
                        Icons.Rounded.Apps,
                        stringResource(R.string.space_empty_title),
                        stringResource(R.string.space_empty),
                    ) {
                        androidx.compose.material3.Button(onClick = { adding = true }) { Text(stringResource(R.string.space_add_apps)) }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(88.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(current.apps, key = { it.packageName }) { app ->
                            AppTile(
                                app = app,
                                onLaunch = {
                                    if (!space.launch(app, current.user)) send(SpaceOwner.CMD_LAUNCH, listOf(app.packageName))
                                },
                                onToggleFreeze = {
                                    send(if (app.frozen) SpaceOwner.CMD_UNFREEZE else SpaceOwner.CMD_FREEZE, listOf(app.packageName))
                                },
                                onRemove = { send(SpaceOwner.CMD_REMOVE, listOf(app.packageName)) },
                            )
                        }
                    }
                }
            }
        }
    }

    problem?.let { reason ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text(stringResource(R.string.space_delete)) },
            text = { Text(stringResource(reason) + "\n\n" + stringResource(R.string.space_delete_manual)) },
            confirmButton = {
                TextButton(onClick = {
                    problem = null
                    vault.holdLock = true
                    openRemovalSettings(context, SpaceManager.ProfileType.WORK)
                }) { Text(stringResource(R.string.other_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { problem = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmDelete) {
        com.spiros.app.ui.common.ConfirmSheet(
            title = stringResource(R.string.space_delete),
            lost = stringResource(R.string.space_delete_confirm),
            kept = stringResource(R.string.space_delete_kept),
            confirm = stringResource(R.string.space_delete),
            onConfirm = {
                send(SpaceOwner.CMD_DESTROY)
                space.forget()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun loadState(context: android.content.Context, space: SpaceManager): SpaceState {
    val user = space.spaceUser()
    if (user == null) {
        val check = DeviceCompat.workProfileCheck(context)
        val others = runCatching { space.otherProfiles() }.getOrDefault(emptyList())
        return if (check.level == DeviceCompat.Level.OK) SpaceState.Absent(others) else SpaceState.Unsupported(check.detail, others)
    }
    if (space.isPaused(user)) return SpaceState.Paused(user)
    return SpaceState.Active(user, runCatching { space.spaceApps(user) }.getOrDefault(emptyList()))
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: SpaceManager.App,
    onLaunch: () -> Unit,
    onToggleFreeze: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onLaunch, onLongClick = { menu = true }),
        ) {
            Box {
                Image(
                    app.icon.asImageBitmap(),
                    contentDescription = null,
                    // Frozen apps look "switched off": greyscale with a snowflake.
                    colorFilter = if (app.frozen) {
                        androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) },
                        )
                    } else {
                        null
                    },
                    modifier = Modifier.size(56.dp),
                )
                if (app.frozen) {
                    Icon(
                        Icons.Rounded.AcUnit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                            .padding(3.dp),
                    )
                }
            }
            Text(
                app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (app.frozen) {
                Text(
                    stringResource(R.string.space_frozen),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.space_open_app)) },
                onClick = { menu = false; onLaunch() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (app.frozen) R.string.space_unfreeze else R.string.space_freeze)) },
                onClick = { menu = false; onToggleFreeze() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.space_remove_app)) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}

/**
 * Reliable link to SpirOS inside the space: offers Android's "connected apps" approval
 * (works on every skin), and shows the connection details to report a problem.
 */
@Composable
private fun SpaceConnection(space: SpaceManager, refresh: Int, onLeaveApp: () -> Unit) {
    val context = LocalContext.current
    var details by remember { mutableStateOf(false) }
    val ready = remember(refresh) { space.directReady() }
    val connect = remember(refresh) { space.connectIntent() }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!ready && connect != null) {
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.space_connect_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.space_connect_desc), style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.Button(onClick = {
                        onLeaveApp()
                        runCatching { context.startActivity(connect) }
                    }) { Text(stringResource(R.string.space_connect)) }
                }
            }
        }
        TextButton(onClick = { details = !details }) {
            Text(stringResource(if (ready) R.string.space_link_direct else R.string.space_link_details))
        }
        if (details) {
            val lines = remember(refresh) { space.diagnostics() }
            val text = lines.joinToString("\n") { (k, v) -> "$k: $v" }
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column(Modifier.padding(12.dp)) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("SpirOS space", text))
                    }) { Text(stringResource(R.string.ai_copy)) }
                }
            }
        }
    }
}
