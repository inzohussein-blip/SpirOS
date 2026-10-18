package com.spiros.app.ui.space

import android.app.Activity
import android.os.UserHandle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.WbSunny
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
    data class Unsupported(val reason: String) : SpaceState
    data object Absent : SpaceState
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

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh) {
        state = withContext(Dispatchers.IO) { loadState(context, space) }
    }

    fun message(res: Int) = scope.launch { snackbar.showSnackbar(context.getString(res)) }

    val provisioning = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vault.holdLock = false
        busy = false
        if (result.resultCode != Activity.RESULT_OK) message(R.string.space_create_failed)
        // The new profile finishes setting itself up in the background; look again shortly.
        scope.launch {
            repeat(5) {
                refresh++
                delay(1500)
            }
        }
    }
    val commands = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vault.holdLock = false
        busy = false
        refresh++
    }

    fun send(cmd: String, packages: List<String> = emptyList()) {
        val intent = space.command(cmd, packages)
        if (intent == null) {
            message(R.string.space_unreachable)
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
                        Icon(Icons.Outlined.AcUnit, contentDescription = stringResource(R.string.space_freeze_all))
                    }
                    IconButton(onClick = { send(SpaceOwner.CMD_UNFREEZE_ALL) }) {
                        Icon(Icons.Outlined.WbSunny, contentDescription = stringResource(R.string.space_unfreeze_all))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Outlined.DeleteForever,
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
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.space_add_apps)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (current) {
                null -> Centered { CircularProgressIndicator() }
                is SpaceState.Unsupported -> Centered {
                    Text(stringResource(R.string.space_intro), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.space_unavailable) + "\n" + current.reason,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.compat_work_profile_fallback),
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center,
                    )
                }
                SpaceState.Absent -> Centered {
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
                    Centered {
                        Text(
                            stringResource(R.string.space_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
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

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.space_delete)) },
            text = { Text(stringResource(R.string.space_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    send(SpaceOwner.CMD_DESTROY)
                    space.forget()
                }) { Text(stringResource(R.string.space_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun loadState(context: android.content.Context, space: SpaceManager): SpaceState {
    val user = space.spaceUser()
    if (user == null) {
        val check = DeviceCompat.workProfileCheck(context)
        return if (check.level == DeviceCompat.Level.OK) SpaceState.Absent else SpaceState.Unsupported(check.detail)
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
                .combinedClickable(onClick = onLaunch, onLongClick = { menu = true })
                .alpha(if (app.frozen) 0.45f else 1f),
        ) {
            Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp))
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
