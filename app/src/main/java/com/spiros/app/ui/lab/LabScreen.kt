package com.spiros.app.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Intent
import com.spiros.app.R
import com.spiros.app.vault.Vault
import com.spiros.app.engine.AlpineGuest
import com.spiros.app.engine.LabSession
import com.spiros.app.engine.LabVm
import com.spiros.app.engine.QemuRunner
import com.spiros.app.ui.SpirTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(vault: Vault, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { QemuRunner(context) }
    val maxCores = remember { Runtime.getRuntime().availableProcessors().coerceIn(2, 8) }
    var cores by remember { mutableFloatStateOf(minOf(4, maxCores).toFloat()) }
    var ramMb by remember { mutableFloatStateOf(512f) }
    var download by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var graphics by remember { mutableStateOf(true) }
    var showScreen by remember { mutableStateOf(true) }
    val screen by LabSession.screen.collectAsState()

    val running by LabSession.running.collectAsState()
    val console by LabSession.console.collectAsState()
    val bootSeconds by LabSession.bootSeconds.collectAsState()
    val version by produceState<String?>(null) {
        value = if (runner.isBundled) withContext(Dispatchers.IO) { runner.version() } else null
    }

    fun submit() {
        val client = screen
        if (client != null && showScreen) client.type(input + "\n") else LabSession.send(input)
        input = ""
    }

    fun boot() {
        error = null
        scope.launch {
            try {
                download = 0f
                val files = AlpineGuest.ensure(context) { p -> download = p }
                download = null
                val args = withContext(Dispatchers.IO) {
                    LabVm.args(context, files, cores.roundToInt(), ramMb.roundToInt(), graphics)
                }
                LabSession.start(
                    runner,
                    args,
                    vncSocket = if (graphics) LabVm.vncSocket(context).path else null,
                )
            } catch (e: Exception) {
                download = null
                error = e.message ?: e.toString()
            }
        }
    }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.section_lab), onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (runner.isBundled) version ?: "…" else stringResource(R.string.lab_not_bundled),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.lab_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    bootSeconds?.let {
                        Text(
                            stringResource(R.string.lab_boot_time, "%.1f".format(it)),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            if (!running) {
                Text(stringResource(R.string.lab_cores, cores.roundToInt()))
                Slider(value = cores, onValueChange = { cores = it }, valueRange = 1f..maxCores.toFloat(), steps = (maxCores - 2).coerceAtLeast(0))
                Text(stringResource(R.string.lab_ram, ramMb.roundToInt()))
                Slider(value = ramMb, onValueChange = { ramMb = it }, valueRange = 256f..2048f, steps = 6)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lab_graphics), modifier = Modifier.weight(1f))
                    Switch(checked = graphics, onCheckedChange = { graphics = it })
                }
                Button(
                    onClick = { boot() },
                    enabled = runner.isBundled && download == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.lab_boot_alpine)) }
            } else {
                OutlinedButton(onClick = LabSession::stop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.lab_stop))
                }
            }
            download?.let {
                Text(stringResource(R.string.lab_downloading))
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (console.isNotEmpty()) {
                TextButton(onClick = {
                    // Screenshots are blocked in SpirOS, so offer the log as text.
                    val text = (listOfNotNull(version, error) + console.takeLast(300)).joinToString("\n")
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    vault.holdLock = true
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.lab_share_log)) }
            }

            val client = screen
            if (running && client != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showScreen, onClick = { showScreen = true }, label = { Text(stringResource(R.string.lab_tab_screen)) })
                    FilterChip(selected = !showScreen, onClick = { showScreen = false }, label = { Text(stringResource(R.string.lab_tab_terminal)) })
                }
            }
            if (running && client != null && showScreen) {
                VmScreen(client, Modifier.weight(1f))
            } else {
                Console(console, Modifier.weight(1f))
            }

            if (running) {
                Row {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.lab_input_hint)) },
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { submit() }) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.lab_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun Console(lines: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    // Terminal output is always left-to-right.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxWidth()
                .background(Color(0xFF05070C))
                .padding(8.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(line, color = Color(0xFFB8F5C8), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}
