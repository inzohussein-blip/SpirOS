package com.spiros.app.ui.linux

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.ai.AiChat
import com.spiros.app.ai.AiServer
import com.spiros.app.remote.Engine
import com.spiros.app.remote.LinuxApps
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What the private AI suggested for a request. */
private data class Suggestion(val command: String, val explain: String, val risky: Boolean)

private const val SYSTEM = """You turn a request into ONE shell command for SpirOS Linux: Ubuntu 26.04 inside proot on an Android phone, as root, bash, apt available, no systemd, no snap, no sudo needed.
Reply with JSON only, no other text: {"command": "...", "explain": "one short sentence in the user's language", "risky": true|false}
risky = true if it deletes or overwrites files, changes system settings, or downloads and runs code. Prefer safe, read-only commands when the request is a question."""

/** Commands that are dangerous whatever the model says. */
private val DANGER = Regex("""(rm\s+-[a-zA-Z]*r|mkfs|dd\s+if=|>\s*/dev/|chmod\s+-R\s+777\s+/|:\(\)\s*\{|curl[^|]*\|\s*(ba)?sh|wget[^|]*\|\s*(ba)?sh)""")

/**
 * "Ask in your own words": the private AI (llama.cpp in the engine, nothing leaves
 * the phone) suggests a Linux command with a one-line explanation; the user reads
 * it and runs it in a terminal window, or copies it. Nothing runs by itself.
 */
@Composable
fun CommandAssistant() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiState by Engine.aiState.collectAsState()
    var request by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf<Suggestion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.assist_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            }
            if (aiState != AiServer.State.READY) {
                Text(stringResource(R.string.assist_need_ai), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                placeholder = { Text(stringResource(R.string.assist_hint)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Button(enabled = request.isNotBlank() && !thinking, onClick = {
                thinking = true; suggestion = null; error = null
                scope.launch {
                    val reply = StringBuilder()
                    runCatching {
                        AiChat.complete(context, SYSTEM, listOf(AiChat.Message("user", request.trim()))) { reply.append(it) }
                        val text = reply.toString()
                        val json = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
                        val command = json.getString("command").trim()
                        require(command.isNotEmpty())
                        Suggestion(command, json.optString("explain"), json.optBoolean("risky") || DANGER.containsMatchIn(command))
                    }.onSuccess { suggestion = it }.onFailure { error = context.getString(R.string.assist_failed) }
                    thinking = false
                }
            }) {
                if (thinking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.assist_suggest))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            suggestion?.let { s ->
                Text(
                    s.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(12.dp),
                )
                if (s.explain.isNotBlank()) Text(s.explain, style = MaterialTheme.typography.bodySmall)
                if (s.risky) Text(stringResource(R.string.assist_risky), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runInTerminal(context, s.command) }) { Text(stringResource(R.string.assist_run)) }
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("command", s.command))
                    }) { Text(stringResource(R.string.assist_copy)) }
                }
            }
        }
    }
}

/** Opens a terminal window in Linux that runs [command] and waits for Enter. */
private fun runInTerminal(context: android.content.Context, command: String) {
    // Base64 keeps any quotes in the command intact on its way through the shells.
    val b64 = Base64.encodeToString(command.toByteArray(), Base64.NO_WRAP)
    val terminal = "lxterminal -t Assistant -e bash -c \"echo $b64 | base64 -d > /tmp/spiros-assist.sh; bash /tmp/spiros-assist.sh; echo; read -p Enter\""
    LinuxApps.launch(context, LinuxApps.App("assistant", R.string.assist_title, terminal, R.drawable.ic_sc_terminal))
}
