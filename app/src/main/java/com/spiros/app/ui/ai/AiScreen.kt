package com.spiros.app.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ArrowDropDown
import com.spiros.app.ai.AiChat
import com.spiros.app.ai.AiModels
import com.spiros.app.ai.AiServer
import com.spiros.app.remote.Engine
import com.spiros.app.ui.common.TaskCard
import androidx.compose.runtime.produceState
import com.spiros.app.ui.SpirTopBar
import kotlinx.coroutines.launch

private val PRESETS = listOf(
    R.string.ai_preset_general to AiChat.PRESET_GENERAL,
    R.string.ai_preset_trading to AiChat.PRESET_TRADING,
    R.string.ai_preset_code to AiChat.PRESET_CODE,
)

/** Private, offline AI chat backed by llama.cpp inside the Linux system. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(onBack: () -> Unit, onOpenLinux: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Engine.status.collectAsState()
    val state = status.aiState
    val ram = remember { AiModels.phoneRamGb(context) }
    var selected by remember { mutableStateOf(AiModels.byFileName(status.aiModel) ?: AiModels.recommended(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableIntStateOf(0) }
    val vault = (context.applicationContext as com.spiros.app.SpirApp).vault
    // The conversation is kept encrypted in the vault (never in plain files).
    val messages = remember { mutableStateListOf<AiChat.Message>().apply { addAll(loadHistory(vault)) } }
    var modelMenu by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var answering by remember { mutableStateOf(false) }
    val downloaded by produceState(emptyList<String>(), refresh, status.connected) {
        value = Engine.parseStrings(Engine.call(context) { it.downloadedModels() })
    }
    var aiLog by remember { mutableStateOf(emptyList<String>()) }
    // Runs in the engine in the background: leaving this screen does not stop it.
    fun download(model: AiModels.Model) {
        error = null
        scope.launch {
            Engine.call(context) {
                it.clearTask()
                it.downloadModel(model.url, model.fileName, Engine.callback(onProgress = { _, _ -> }, onDone = { e ->
                    // "busy": a Linux install is running; other errors show in the task card.
                    if (e == "busy") error = context.getString(R.string.task_busy)
                    refresh++
                }))
            }
        }
    }
    LaunchedEffect(status.taskActive) { if (!status.taskActive) refresh++ }
    LaunchedEffect(messages.size, answering) {
        if (!answering) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveHistory(vault, messages.toList()) }
    }
    LaunchedEffect(state) {
        if (state == AiServer.State.FAILED) aiLog = Engine.call(context) { it.aiLog() }.orEmpty()
    }

    Scaffold(
        topBar = {
            SpirTopBar(stringResource(R.string.ai_title), onBack) {
                if (state == AiServer.State.READY) {
                    // Switch the model from the title bar (only models already downloaded).
                    Box {
                        androidx.compose.material3.AssistChip(
                            onClick = { modelMenu = true },
                            label = { Text(AiModels.byFileName(status.aiModel)?.name?.substringBefore(" ·") ?: status.aiModel, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
                        )
                        androidx.compose.material3.DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            AiModels.catalog.filter { it.fileName in downloaded }.forEach { m ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(m.name) },
                                    onClick = {
                                        modelMenu = false
                                        if (m.fileName != status.aiModel) {
                                            selected = m
                                            Engine.startAi(context, m)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (state == AiServer.State.READY || state == AiServer.State.LOADING) {
                    IconButton(onClick = { messages.clear() }) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.ai_clear))
                    }
                    IconButton(onClick = { Engine.send(context) { it.stopAi() } }) {
                        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = stringResource(R.string.ai_stop))
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                !status.connected || !status.linuxInstalled -> Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.ai_needs_linux))
                    Button(onClick = onOpenLinux) { Text(stringResource(R.string.dock_linux)) }
                }

                state == AiServer.State.READY -> Chat(
                    messages = messages,
                    answering = answering,
                    preset = preset,
                    onPreset = { preset = it },
                    input = input,
                    onInput = { input = it },
                    onSend = { suggestion ->
                        val text = (suggestion ?: input).trim()
                        if (text.isEmpty() || answering) return@Chat
                        input = ""
                        messages += AiChat.Message("user", text)
                        val history = messages.toList()
                        messages += AiChat.Message("assistant", "")
                        answering = true
                        scope.launch {
                            val reply = StringBuilder()
                            runCatching {
                                AiChat.complete(context, PRESETS[preset].second, history) { piece ->
                                    reply.append(piece)
                                    val text = reply.toString()
                                    scope.launch { messages[messages.lastIndex] = AiChat.Message("assistant", text) }
                                }
                            }.onFailure { e ->
                                messages[messages.lastIndex] = AiChat.Message("assistant", "⚠️ ${e.message}")
                            }
                            answering = false
                        }
                    },
                )

                else -> Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.ai_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.ai_ram, ram, AiModels.recommended(context).name),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    refresh // re-read download state
                    AiModels.catalog.forEach { model ->
                        val isDownloaded = model.fileName in downloaded
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(model.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        stringResource(R.string.ai_model_info, model.sizeMb, model.minRamGb) +
                                            if (model.minRamGb > ram) " · " + stringResource(R.string.ai_model_too_big) else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (model.minRamGb > ram) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilterChip(
                                    selected = selected.id == model.id,
                                    onClick = { selected = model },
                                    label = { Text(stringResource(if (isDownloaded) R.string.ai_ready else R.string.ai_not_downloaded)) },
                                )
                            }
                        }
                    }

                    val modelTask = status.task?.takeIf { it.kind == "model" }
                    if (modelTask != null && !modelTask.active && modelTask.error != null) {
                        TaskCard(
                            modelTask,
                            title = AiModels.byFileName(modelTask.name)?.name ?: modelTask.name,
                            onRetry = AiModels.byFileName(modelTask.name)?.let { m -> { download(m) } },
                        )
                    }
                    when {
                        modelTask != null && modelTask.active -> TaskCard(
                            modelTask,
                            title = stringResource(R.string.ai_downloading_model, AiModels.byFileName(modelTask.name)?.name ?: modelTask.name),
                        )
                        state == AiServer.State.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp))
                            Text(stringResource(R.string.ai_loading))
                        }
                        selected.fileName !in downloaded -> Button(onClick = { download(selected) }) {
                            Text(stringResource(R.string.ai_download, selected.name))
                        }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { Engine.startAi(context, selected) }) { Text(stringResource(R.string.ai_start)) }
                            OutlinedButton(onClick = {
                                val name = selected.fileName
                                scope.launch { Engine.call(context) { it.deleteModel(name) }; refresh++ }
                            }) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                    if (state == AiServer.State.FAILED) {
                        Text(stringResource(R.string.ai_failed), color = MaterialTheme.colorScheme.error)
                        Text(aiLog.takeLast(15).joinToString("\n"), style = MaterialTheme.typography.labelSmall)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** Ready-made questions per preset, shown before the first message. */
private val SUGGESTIONS = listOf(
    listOf(R.string.ai_s_general_1, R.string.ai_s_general_2, R.string.ai_s_general_3),
    listOf(R.string.ai_s_trading_1, R.string.ai_s_trading_2, R.string.ai_s_trading_3),
    listOf(R.string.ai_s_code_1, R.string.ai_s_code_2, R.string.ai_s_code_3),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Chat(
    messages: List<AiChat.Message>,
    answering: Boolean,
    preset: Int,
    onPreset: (Int) -> Unit,
    input: String,
    onInput: (String) -> Unit,
    /** Sends [input], or the given suggestion. */
    onSend: (String?) -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
            PRESETS.forEachIndexed { i, (label, _) ->
                FilterChip(selected = preset == i, onClick = { onPreset(i) }, label = { Text(stringResource(label)) })
            }
        }
        if (messages.isEmpty()) {
            // Empty chat: a few ready-made questions to start with.
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
                Text(stringResource(R.string.ai_empty_title), style = MaterialTheme.typography.titleMedium)
                SUGGESTIONS[preset].forEach { res ->
                    val text = stringResource(res)
                    androidx.compose.material3.SuggestionChip(onClick = { onSend(text) }, label = { Text(text) })
                }
            }
        } else LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { m ->
                val mine = m.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Box(
                        Modifier
                            .widthIn(max = 320.dp)
                            .background(
                                if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(14.dp),
                            )
                            .padding(10.dp),
                    ) {
                        if (m.text.isEmpty()) TypingDots() else androidx.compose.foundation.text.selection.SelectionContainer { Text(m.text) }
                    }
                    if (!mine && m.text.isNotEmpty()) {
                        Row {
                            IconButton(onClick = {
                                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                                clip.setPrimaryClip(android.content.ClipData.newPlainText("SpirOS AI", m.text))
                            }) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.ai_copy), Modifier.padding(2.dp)) }
                            IconButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT, m.text)
                                (context.applicationContext as com.spiros.app.SpirApp).vault.holdLock = true
                                runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
                            }) { Icon(Icons.Rounded.Share, stringResource(R.string.ai_share), Modifier.padding(2.dp)) }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                placeholder = { Text(stringResource(R.string.ai_input_hint)) },
                modifier = Modifier.weight(1f),
                maxLines = 5,
            )
            IconButton(onClick = { onSend(null) }, enabled = !answering) {
                if (answering) CircularProgressIndicator(Modifier.padding(4.dp)) else
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = stringResource(R.string.lab_send))
            }
        }
    }
}

/** "…" that pulses while the model is thinking. */
@Composable
private fun TypingDots() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val alpha by t.animateFloat(
                0.2f, 1f,
                androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(500, delayMillis = i * 160),
                    androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

private const val HISTORY = "ai-chat"

private fun loadHistory(vault: com.spiros.app.vault.Vault): List<AiChat.Message> = runCatching {
    val json = org.json.JSONArray(String(vault.files.readPrivate(HISTORY) ?: return emptyList()))
    (0 until json.length()).map { json.getJSONObject(it) }.map { AiChat.Message(it.getString("role"), it.getString("text")) }
}.getOrDefault(emptyList())

private fun saveHistory(vault: com.spiros.app.vault.Vault, messages: List<AiChat.Message>) {
    runCatching {
        if (messages.isEmpty()) {
            vault.files.writePrivate(HISTORY, null)
        } else {
            val json = org.json.JSONArray()
            messages.takeLast(200).forEach { json.put(org.json.JSONObject().put("role", it.role).put("text", it.text)) }
            vault.files.writePrivate(HISTORY, json.toString().toByteArray())
        }
    }
}
