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
import com.spiros.app.remote.Engine
import com.spiros.app.remote.EngineInstaller
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
import com.spiros.app.security.Panic
import com.spiros.app.perf.Performance
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.ui.lab.VmScreen
import com.spiros.app.ui.Pip
import androidx.compose.runtime.DisposableEffect
import com.spiros.app.ui.common.BootOverlay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/**
 * The SpirOS Linux desktop: Debian + Xfce with touch controls. Linux itself runs in the
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
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
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
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_back), tint = Color.White)
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.linux_type_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSend = { client.type(input + "\n"); input = "" }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { client.type(input); input = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.lab_send), tint = Color.White)
                    }
                    IconButton(onClick = { (context as? Activity)?.let(Pip::enter) }) {
                        Icon(Icons.Rounded.PictureInPicture, contentDescription = stringResource(R.string.linux_pip), tint = Color.White)
                    }
                    IconButton(onClick = { Panic.trigger(context) }) {
                        Icon(Icons.Rounded.Warning, contentDescription = stringResource(R.string.panic), tint = Color(0xFFFF6B6B))
                    }
                    IconButton(onClick = { Engine.stopDesktop(context) }) {
                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = stringResource(R.string.linux_stop), tint = Color(0xFFFF6B6B))
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
                } else {
                    VmScreen(client, Modifier.weight(1f))
                }
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
                !installed -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.linux_install_desc))
                        val p = progress
                        if (p == null) {
                            Button(onClick = {
                                error = null
                                progress = "DOWNLOADING" to 0f
                                scope.launch {
                                    Engine.call(context) {
                                        it.installLinux(Engine.callback(
                                            onProgress = { stage, f -> progress = stage to f },
                                            onDone = { e ->
                                                error = e
                                                progress = null
                                                Engine.refresh()
                                            },
                                        ))
                                    } ?: run { progress = null }
                                }
                            }) { Text(stringResource(R.string.linux_install)) }
                        } else {
                            Text(
                                stringResource(
                                    when (p.first) {
                                        "VERIFYING" -> R.string.linux_stage_verify
                                        "EXTRACTING" -> R.string.linux_stage_extract
                                        else -> R.string.linux_stage_download
                                    },
                                ) + " ${(p.second * 100).toInt()}%",
                            )
                            LinearProgressIndicator(progress = { p.second }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                else -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            onClick = { Engine.startDesktop(context, resolution) },
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
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.linux_remove)) },
            text = { Text(stringResource(R.string.linux_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    Engine.stopDesktop(context)
                    Engine.send(context) { it.removeLinux() }
                }) { Text(stringResource(R.string.linux_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
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
