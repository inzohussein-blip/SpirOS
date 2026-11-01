package com.spiros.app.ui.linux

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.spiros.app.BuildConfig
import com.spiros.app.R
import com.spiros.app.SpirApp
import com.spiros.app.remote.Engine
import com.spiros.app.remote.EngineInstaller
import com.spiros.app.ui.common.TaskCard
import com.spiros.app.ui.common.StepIndicator
import androidx.compose.runtime.produceState
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
import com.spiros.app.security.Panic
import com.spiros.app.perf.Performance
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.ui.lab.VmScreen
import com.spiros.app.ui.lab.Cursor
import com.spiros.app.ui.lab.Touchpad
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import com.spiros.app.ui.Pip
import androidx.compose.runtime.DisposableEffect
import com.spiros.app.ui.common.BootOverlay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/**
 * The SpirOS Linux desktop: Ubuntu in Linux Mint style (Xfce, MATE or Cinnamon) or the
 * user's own system, with touch controls and an optional touchpad. Linux itself runs in the
 * SpirOS Engine (installed from here on first use); this screen only shows and drives it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Engine.status.collectAsState()
    val running = status.desktopRunning
    val installed = status.linuxInstalled
    val screen by Engine.screen.collectAsState()
    var log by remember { mutableStateOf(emptyList<String>()) }
    var engineInstalled by remember { mutableStateOf(Engine.isInstalled(context)) }
    val installState by EngineInstaller.state.collectAsState()
    val linuxTask = status.task?.takeIf { it.kind == "linux" }
    var changing by remember { mutableStateOf(false) }
    var lastInstall by remember { mutableStateOf<Triple<String, String?, String?>?>(null) }
    // Connect to the engine (and notice when it has just been installed).
    LaunchedEffect(installState) {
        engineInstalled = Engine.isInstalled(context)
        if (engineInstalled) Engine.connect(context)
    }
    // Back in SpirOS with the desktop still running: show it again.
    LaunchedEffect(running) {
        if (running) Engine.attachScreen(Performance.linuxFps(context))
    }
    // The desktop's log (boot screen, and the reason if it stops).
    LaunchedEffect(status.connected, running) {
        while (status.connected) {
            Engine.call(context) { it.desktopLog() }?.let { log = it }
            if (!running) break
            delay(1500)
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var resolution by remember { mutableStateOf(Performance.linuxResolution(context)) }
    var confirmRemove by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var touchpad by remember { mutableStateOf(Performance.touchpad(context)) }
    var typing by remember { mutableStateOf(false) }
    // A TV / monitor (HDMI, USB-C or wireless) shows the desktop when allowed in Settings.
    val external = rememberExternalDisplay()
    val externalNow = external?.takeIf { Performance.externalDisplay(context) }
    var accountsVersion by remember { mutableIntStateOf(0) }
    var accountSkipped by remember { mutableStateOf(false) }
    val cursor = remember { Cursor() }

    // Full-screen desktop while running.
    val client = screen
    val inPip by Pip.active.collectAsState()
    if (running && client != null && inPip) {
        // Picture-in-picture: just the live desktop, no controls.
        VmScreen(client, Modifier.fillMaxSize().background(Color.Black), controls = false)
        return
    }
    if (running && client != null) {
        DisposableEffect(Unit) {
            Pip.desktopVisible = true
            onDispose { Pip.desktopVisible = false }
        }
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
          Box(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                // Typing bar only while the keyboard tool is on: the desktop gets the
                // whole screen otherwise.
                if (typing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.linux_type_hint)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onSend = { client.type(input + "\n"); input = "" }),
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        IconButton(onClick = { client.type(input); input = "" }) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.lab_send), tint = Color.White)
                        }
                    }
                }
                // Digital-rain boot screen with the real log until the desktop draws.
                val firstFrame by client.bitmap.collectAsState()
                val haptics = LocalHapticFeedback.current
                LaunchedEffect(firstFrame != null) {
                    if (firstFrame != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                if (firstFrame == null) {
                    BootOverlay(log, stringResource(R.string.boot_linux), Modifier.weight(1f))
                } else if (externalNow != null) {
                    // Desktop on the TV / monitor; the phone is the touchpad and keyboard.
                    ExternalDesktop(externalNow, client, cursor)
                    Text(
                        stringResource(R.string.external_showing, externalNow.name),
                        color = Color(0xFF9FB0D0),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Touchpad(client, cursor, Modifier.weight(1f).fillMaxWidth())
                } else if (touchpad) {
                    // Landscape: touchpad beside the screen; portrait: below it.
                    val wide = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
                    if (wide) {
                        Row(Modifier.weight(1f)) {
                            VmScreen(client, Modifier.weight(2.2f), cursor = cursor)
                            Touchpad(client, cursor, Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        VmScreen(client, Modifier.weight(1.6f), cursor = cursor)
                        Touchpad(client, cursor, Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    VmScreen(client, Modifier.weight(1f))
                }
            }
            // One floating, draggable button holds every desktop tool.
            FloatingDock(
                tools = listOf(
                    DockTool(Icons.Rounded.Close, stringResource(R.string.action_back), Color.White, onBack),
                    DockTool(Icons.Rounded.Keyboard, stringResource(R.string.linux_type_hint), if (typing) Color(0xFF7CE7C3) else Color.White) {
                        typing = !typing
                    },
                    DockTool(
                        Icons.Rounded.Mouse,
                        stringResource(if (touchpad) R.string.touchpad_hide else R.string.touchpad_show),
                        if (touchpad) Color(0xFF7CE7C3) else Color.White,
                    ) {
                        touchpad = !touchpad
                        Performance.setTouchpad(context, touchpad)
                    },
                    DockTool(Icons.Rounded.PictureInPicture, stringResource(R.string.linux_pip), Color.White) {
                        (context as? Activity)?.let(Pip::enter)
                    },
                    DockTool(Icons.Rounded.Warning, stringResource(R.string.panic), Color(0xFFFF6B6B)) { Panic.trigger(context) },
                    DockTool(Icons.Rounded.PowerSettingsNew, stringResource(R.string.linux_stop), Color(0xFFFF6B6B)) {
                        Engine.stopDesktop(context)
                    },
                ),
            )
          }
        }
        return
    }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.linux_title), onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.linux_intro), style = MaterialTheme.typography.bodyMedium)

            // Three-step setup: choose → download → your account.
            val users by produceState(emptyList<Engine.User>(), status.connected, installed, accountsVersion) {
                value = if (status.connected && installed) Engine.parseUsers(Engine.call(context) { it.users() }) else emptyList()
            }
            val needsAccount = installed && users.none { it.uid != 0 } && !Performance.accountPromptDone(context) && !accountSkipped
            val step = when {
                linuxTask?.active == true -> 1
                needsAccount -> 2
                !installed || changing -> 0
                else -> -1
            }
            if (engineInstalled && status.connected && step >= 0) {
                StepIndicator(
                    listOf(
                        stringResource(R.string.wizard_choose),
                        stringResource(R.string.wizard_download),
                        stringResource(R.string.wizard_account),
                    ),
                    step,
                )
            }
            if (step == 2 && linuxTask?.active != true) {
                AccountStep(
                    onCreated = { accountsVersion++ },
                    onSkip = {
                        Performance.setAccountPromptDone(context, true)
                        accountSkipped = true
                    },
                )
            }

            when {
                !engineInstalled -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.engine_needed), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.engine_needed_desc), style = MaterialTheme.typography.bodySmall)
                        EngineInstallButton(installState)
                    }
                }
                !status.connected -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text(stringResource(R.string.engine_connecting))
                }
                status.arch.isEmpty() || !status.proot ->
                    Text(stringResource(R.string.linux_unsupported), color = MaterialTheme.colorScheme.error)
                linuxTask?.active == true -> Unit // progress card below
                !installed || changing -> DistroChooser(
                    current = status.distro,
                    onInstall = { desktop, url, sha, file ->
                        error = null
                        changing = false
                        lastInstall = Triple(desktop, url, sha).takeIf { file == null }
                        Engine.installDistro(context, desktop, url, sha, file)
                    },
                    onCancel = if (installed) ({ changing = false }) else null,
                )
                else -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.distro_current, distroName(status.distro)),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { changing = true }) { Text(stringResource(R.string.distro_change)) }
                        }
                        Text(stringResource(R.string.linux_resolution), style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            Performance.RESOLUTIONS.forEach { r ->
                                FilterChip(
                                    selected = resolution == r,
                                    onClick = {
                                        resolution = r
                                        Performance.setLinuxResolution(context, r)
                                    },
                                    label = { Text(r) },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val geometry = externalNow?.takeIf { Performance.externalMatch(context) }
                                    ?.let(ExternalDisplay::geometryFor) ?: resolution
                                Engine.startDesktop(context, geometry)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.linux_start)) }
                        Text(
                            stringResource(R.string.linux_touch_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { confirmRemove = true }) {
                            Text(stringResource(R.string.linux_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            linuxTask?.let { task ->
                if (task.active || task.error != null) {
                    TaskCard(
                        task,
                        title = stringResource(R.string.task_installing, distroName(task.name)),
                        onRetry = (lastInstall ?: Triple(task.name, null, null).takeIf { task.name != "custom" })?.let { (d, u, h) ->
                            { Engine.installDistro(context, d, u, h) }
                        },
                    )
                }
            }
            if (engineInstalled && status.connected && status.version < BuildConfig.VERSION_CODE) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.engine_update_desc), style = MaterialTheme.typography.bodySmall)
                        EngineInstallButton(installState, update = true)
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                stringResource(R.string.linux_android_limits),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (log.isNotEmpty()) {
                Text(stringResource(R.string.linux_last_log), style = MaterialTheme.typography.titleSmall)
                Text(
                    log.takeLast(40).joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF05070C)).padding(8.dp),
                    color = Color(0xFFB8F5C8),
                )
            }
        }
    }

    if (confirmRemove) {
        com.spiros.app.ui.common.ConfirmSheet(
            title = stringResource(R.string.linux_remove),
            lost = stringResource(R.string.linux_remove_confirm),
            kept = stringResource(R.string.linux_remove_kept),
            confirm = stringResource(R.string.linux_remove),
            onConfirm = {
                Engine.stopDesktop(context)
                Engine.send(context) { it.removeLinux() }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

/** Download, verify (checksum + SpirOS signature) and install the SpirOS Engine. */
@Composable
private fun EngineInstallButton(state: EngineInstaller.State, update: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    when (state) {
        is EngineInstaller.State.Downloading -> {
            Text(stringResource(R.string.engine_downloading, (state.fraction * 100).toInt()))
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
        }
        EngineInstaller.State.Verifying, EngineInstaller.State.Installing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp))
            Text(stringResource(R.string.engine_installing))
        }
        else -> {
            if (state is EngineInstaller.State.Failed) {
                Text(stringResource(R.string.engine_failed, state.message), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = {
                // Android's settings / install screens must not lock SpirOS behind them.
                (context.applicationContext as SpirApp).vault.holdLock = true
                if (!EngineInstaller.canInstall(context)) {
                    runCatching { context.startActivity(EngineInstaller.allowInstallsIntent(context)) }
                } else {
                    scope.launch { EngineInstaller.install(context) }
                }
            }) { Text(stringResource(if (update) R.string.engine_update else R.string.engine_install)) }
            if (!EngineInstaller.canInstall(context)) {
                Text(
                    stringResource(R.string.engine_allow_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Last setup step: your own Linux account (admin), used for the desktop. */
@Composable
private fun AccountStep(onCreated: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wizard_account_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.wizard_account_desc), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.lowercase().trim() },
                label = { Text(stringResource(R.string.accounts_username)) },
                singleLine = true,
                isError = name.isNotEmpty() && !com.spiros.app.linux.LinuxAccounts.isValidName(name),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.accounts_password)) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                supportingText = { Text(stringResource(R.string.accounts_password_rule)) },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy && com.spiros.app.linux.LinuxAccounts.isValidName(name) &&
                        com.spiros.app.linux.LinuxAccounts.isValidPassword(password),
                    onClick = {
                        busy = true
                        val n = name
                        val p = password
                        scope.launch {
                            val result = Engine.call(context) { e ->
                                (e.account("add", n, p, true) ?: "").also { if (it.isEmpty()) e.setDesktopUser(n) }
                            } ?: "engine not connected"
                            busy = false
                            if (result.isEmpty()) {
                                com.spiros.app.ui.common.Haptics.success(view)
                                Performance.setAccountPromptDone(context, true)
                                onCreated()
                            } else {
                                com.spiros.app.ui.common.Haptics.error(view)
                                error = result
                            }
                        }
                    },
                ) { Text(stringResource(R.string.wizard_account_create)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.wizard_skip)) }
            }
        }
    }
}
