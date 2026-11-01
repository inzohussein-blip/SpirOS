package com.spiros.app.ui.linux

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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PowerSettingsNew
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
import com.spiros.app.linux.LinuxDistro
import com.spiros.app.linux.LinuxSession
import com.spiros.app.perf.Performance
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.ui.lab.VmScreen
import kotlinx.coroutines.launch

private const val LINUX_EDITION_URL = "https://github.com/inzohussein-blip/SpirOS/releases/download/dev-latest/SpirOS-Linux.apk"


/** The SpirOS Linux desktop: Debian + Xfce with touch controls (speed edition). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by LinuxSession.running.collectAsState()
    val screen by LinuxSession.screen.collectAsState()
    val log by LinuxSession.log.collectAsState()
    var installed by remember { mutableStateOf(LinuxDistro.isInstalled(context)) }
    var progress by remember { mutableStateOf<Pair<LinuxDistro.Stage, Float>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolution by remember { mutableStateOf(Performance.linuxResolution(context)) }
    var confirmRemove by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    // Full-screen desktop while running.
    val client = screen
    if (running && client != null) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_back), tint = Color.White)
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
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.lab_send), tint = Color.White)
                    }
                    IconButton(onClick = LinuxSession::stop) {
                        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = stringResource(R.string.linux_stop), tint = Color(0xFFFF6B6B))
                    }
                }
                VmScreen(client, Modifier.weight(1f))
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
                BuildConfig.FLAVOR != "speed" -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.linux_needs_edition))
                        Button(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LINUX_EDITION_URL))) }
                        }) { Text(stringResource(R.string.linux_get_edition)) }
                    }
                }
                LinuxDistro.arch() == null || !LinuxSession.prootAvailable(context) ->
                    Text(stringResource(R.string.linux_unsupported), color = MaterialTheme.colorScheme.error)
                !installed -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.linux_install_desc))
                        val p = progress
                        if (p == null) {
                            Button(onClick = {
                                error = null
                                scope.launch {
                                    try {
                                        LinuxDistro.install(context) { stage, f -> progress = stage to f }
                                        installed = true
                                    } catch (e: Exception) {
                                        error = e.message ?: e.toString()
                                    } finally {
                                        progress = null
                                    }
                                }
                            }) { Text(stringResource(R.string.linux_install)) }
                        } else {
                            Text(
                                stringResource(
                                    when (p.first) {
                                        LinuxDistro.Stage.DOWNLOADING -> R.string.linux_stage_download
                                        LinuxDistro.Stage.VERIFYING -> R.string.linux_stage_verify
                                        LinuxDistro.Stage.EXTRACTING -> R.string.linux_stage_extract
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
                            onClick = { LinuxSession.start(context, resolution, dpi = 120) },
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
                    LinuxSession.stop()
                    LinuxDistro.remove(context)
                    installed = false
                }) { Text(stringResource(R.string.linux_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
