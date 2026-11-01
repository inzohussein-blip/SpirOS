package com.spiros.app.ui.shield

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.shield.Blocklist
import com.spiros.app.shield.ShieldSettings
import com.spiros.app.shield.ShieldVpnService
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShieldScreen(vault: Vault, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val space = remember { SpaceManager(context) }

    val running by ShieldVpnService.running.collectAsState()
    val blocked by ShieldVpnService.blockedCount.collectAsState()
    val recent by ShieldVpnService.recent.collectAsState()
    var upstream by remember { mutableStateOf(ShieldSettings.upstream(context)) }
    var fullList by remember { mutableStateOf(ShieldSettings.useFullList(context)) }
    var spaceShield by remember { mutableStateOf(ShieldSettings.spaceShield(context)) }
    var spaceGoogleOff by remember { mutableStateOf(ShieldSettings.spaceGoogleOff(context)) }
    var updating by remember { mutableStateOf(false) }
    var listVersion by remember { mutableIntStateOf(0) }

    val listSize by produceState(0, listVersion) {
        value = withContext(Dispatchers.IO) {
            Blocklist.ensureLoaded(context)
            Blocklist.size
        }
    }
    val spaceUser by produceState<Any?>(null) { value = withContext(Dispatchers.IO) { space.spaceUser() } }

    fun message(res: Int) = scope.launch { snackbar.showSnackbar(context.getString(res)) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vault.holdLock = false
        if (result.resultCode == Activity.RESULT_OK) ShieldVpnService.start(context) else message(R.string.shield_consent_denied)
    }
    val spaceCommand = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vault.holdLock = false
        if (result.resultCode != Activity.RESULT_OK) message(R.string.space_command_refused)
    }

    fun sendToSpace(cmd: String, extras: Bundle? = null): Boolean {
        val intent = space.command(cmd, extras = extras)
        if (intent == null) {
            message(space.unreachableReason())
            return false
        }
        vault.holdLock = true
        return runCatching { spaceCommand.launch(intent) }.onFailure {
            vault.holdLock = false
            message(R.string.space_unreachable)
        }.isSuccess
    }

    fun restartIfRunning() {
        if (running) {
            ShieldVpnService.stop(context)
            ShieldVpnService.start(context)
        }
    }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.section_shield), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
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
            Text(
                stringResource(R.string.shield_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Isolated space ----
            if (spaceUser != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        ToggleRow(stringResource(R.string.shield_space), spaceShield) { on ->
                            val extras = Bundle().apply {
                                putString(SpaceOwner.EXTRA_UPSTREAM, upstream.name)
                                putBoolean(SpaceOwner.EXTRA_FULL_LIST, fullList)
                            }
                            if (sendToSpace(if (on) SpaceOwner.CMD_SHIELD_ON else SpaceOwner.CMD_SHIELD_OFF, extras)) {
                                spaceShield = on
                                ShieldSettings.setSpaceShield(context, on)
                            }
                        }
                        Text(
                            stringResource(R.string.shield_space_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ToggleRow(stringResource(R.string.shield_google_off), spaceGoogleOff) { on ->
                            if (sendToSpace(if (on) SpaceOwner.CMD_GOOGLE_OFF else SpaceOwner.CMD_GOOGLE_ON)) {
                                spaceGoogleOff = on
                                ShieldSettings.setSpaceGoogleOff(context, on)
                            }
                        }
                        Text(
                            stringResource(R.string.shield_google_off_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- Main phone ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    ToggleRow(stringResource(R.string.shield_phone), running) { on ->
                        if (on) {
                            val request = VpnService.prepare(context)
                            if (request == null) {
                                ShieldVpnService.start(context)
                            } else {
                                vault.holdLock = true
                                consent.launch(request)
                            }
                        } else {
                            ShieldVpnService.stop(context)
                        }
                    }
                    Text(
                        stringResource(R.string.shield_phone_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (running || blocked > 0) {
                        Text(
                            stringResource(R.string.shield_blocked_count, blocked),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        recent.take(10).forEach {
                            Text("· $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ---- Settings ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.shield_resolver), style = MaterialTheme.typography.titleSmall)
                    ShieldSettings.Upstream.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = upstream == option, onClick = {
                                    upstream = option
                                    ShieldSettings.setUpstream(context, option)
                                    restartIfRunning()
                                }),
                        ) {
                            RadioButton(selected = upstream == option, onClick = null)
                            Text("${option.label} (${option.address})", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    ToggleRow(stringResource(R.string.shield_full_list), fullList) { on ->
                        fullList = on
                        ShieldSettings.setUseFullList(context, on)
                        scope.launch {
                            withContext(Dispatchers.IO) { Blocklist.reload(context) }
                            listVersion++
                            restartIfRunning()
                        }
                    }
                    Text(
                        stringResource(R.string.shield_list_size, listSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fullList) {
                        OutlinedButton(
                            enabled = !updating,
                            onClick = {
                                updating = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { Blocklist.downloadFull(context) }
                                    updating = false
                                    listVersion++
                                    message(if (ok) R.string.shield_list_updated else R.string.shield_list_failed)
                                    if (ok) restartIfRunning()
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text(stringResource(if (updating) R.string.shield_updating else R.string.shield_update_now)) }
                    }
                    Text(
                        stringResource(R.string.shield_settings_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Text(
                stringResource(R.string.shield_limits),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
