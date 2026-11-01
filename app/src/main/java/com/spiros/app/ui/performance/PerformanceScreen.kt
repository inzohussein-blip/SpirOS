package com.spiros.app.ui.performance

import android.os.UserHandle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.spiros.app.BuildConfig
import com.spiros.app.R
import com.spiros.app.perf.Performance
import com.spiros.app.shield.ShieldSettings
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.vault.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings > Performance & battery: every option that makes SpirOS lighter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(vault: Vault, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val space = remember { SpaceManager(context) }
    var refresh by remember { mutableIntStateOf(0) }
    val spaceUser by produceState<UserHandle?>(null, refresh) { value = withContext(Dispatchers.IO) { space.spaceUser() } }
    val linux = BuildConfig.FLAVOR == "speed"

    var googleOff by remember { mutableStateOf(ShieldSettings.spaceGoogleOff(context)) }
    var light by remember { mutableStateOf(Performance.linuxLight(context)) }
    var fps by remember { mutableIntStateOf(Performance.linuxFps(context)) }
    var resolution by remember { mutableStateOf(Performance.linuxResolution(context)) }
    var freeze by remember { mutableStateOf(Performance.freezeInBackground(context)) }
    var session by remember { mutableStateOf(Performance.linuxSession(context)) }
    var singleApp by remember { mutableStateOf(Performance.singleApp(context)) }
    var fastCores by remember { mutableStateOf(Performance.aiFastCores(context)) }
    var aiContext by remember { mutableIntStateOf(Performance.aiContext(context)) }
    val fastCoreCount = remember { Performance.fastCoreCount() }

    fun message(res: Int) = scope.launch { snackbar.showSnackbar(context.getString(res)) }

    val commands = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vault.holdLock = false
        refresh++
    }

    fun sendToSpace(cmd: String): Boolean {
        val intent = space.command(cmd) ?: run { message(R.string.space_unreachable); return false }
        vault.holdLock = true
        return runCatching { commands.launch(intent) }.onFailure {
            vault.holdLock = false
            message(R.string.space_unreachable)
        }.isSuccess
    }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.perf_title), onBack) },
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
            Text(stringResource(R.string.perf_intro), style = MaterialTheme.typography.bodySmall)

            // ---- Isolated space ----
            Section(stringResource(R.string.perf_space)) {
                val user = spaceUser
                if (user == null) {
                    Text(stringResource(R.string.perf_no_space), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Toggle(stringResource(R.string.shield_google_off), stringResource(R.string.perf_google_desc), googleOff) { on ->
                        if (sendToSpace(if (on) SpaceOwner.CMD_GOOGLE_OFF else SpaceOwner.CMD_GOOGLE_ON)) {
                            googleOff = on
                            ShieldSettings.setSpaceGoogleOff(context, on)
                        }
                    }
                    val paused = remember(refresh) { space.isPaused(user) }
                    Toggle(stringResource(R.string.perf_pause_space), stringResource(R.string.perf_pause_desc), paused) { on ->
                        if (!space.setPaused(user, on)) message(R.string.space_resume_manual)
                        refresh++
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { sendToSpace(SpaceOwner.CMD_FREEZE_ALL) }) { Text(stringResource(R.string.space_freeze_all)) }
                        OutlinedButton(onClick = { sendToSpace(SpaceOwner.CMD_UNFREEZE_ALL) }) { Text(stringResource(R.string.space_unfreeze_all)) }
                    }
                }
            }

            // ---- Linux ----
            Section(stringResource(R.string.dock_linux)) {
                if (!linux) {
                    Text(stringResource(R.string.perf_linux_edition_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Toggle(stringResource(R.string.perf_light), stringResource(R.string.perf_light_desc), light) {
                        light = it
                        Performance.setLinuxLight(context, it)
                    }
                    Toggle(stringResource(R.string.perf_freeze), stringResource(R.string.perf_freeze_desc), freeze) {
                        freeze = it
                        Performance.setFreezeInBackground(context, it)
                    }
                    Label(stringResource(R.string.perf_fps))
                    Chips(Performance.FPS_OPTIONS.map { "$it" }, "$fps") {
                        fps = it.toInt()
                        Performance.setLinuxFps(context, fps)
                    }
                    Label(stringResource(R.string.linux_resolution))
                    Chips(Performance.RESOLUTIONS, resolution) {
                        resolution = it
                        Performance.setLinuxResolution(context, it)
                    }
                    Label(stringResource(R.string.perf_session))
                    Chips(
                        listOf(stringResource(R.string.perf_session_desktop), stringResource(R.string.perf_session_app)),
                        stringResource(if (session == Performance.Session.DESKTOP) R.string.perf_session_desktop else R.string.perf_session_app),
                    ) { picked ->
                        session = if (picked == context.getString(R.string.perf_session_desktop)) Performance.Session.DESKTOP else Performance.Session.SINGLE_APP
                        Performance.setLinuxSession(context, session)
                    }
                    if (session == Performance.Session.SINGLE_APP) {
                        Chips(Performance.SINGLE_APPS, singleApp) {
                            singleApp = it
                            Performance.setSingleApp(context, it)
                        }
                        Text(stringResource(R.string.perf_session_app_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.perf_restart_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- AI ----
            Section(stringResource(R.string.dock_ai)) {
                if (!linux) {
                    Text(stringResource(R.string.perf_linux_edition_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Toggle(
                        stringResource(R.string.perf_fast_cores),
                        stringResource(R.string.perf_fast_cores_desc, fastCoreCount, Runtime.getRuntime().availableProcessors()),
                        fastCores,
                    ) {
                        fastCores = it
                        Performance.setAiFastCores(context, it)
                    }
                    Label(stringResource(R.string.perf_context))
                    Chips(Performance.CONTEXT_OPTIONS.map { "$it" }, "$aiContext") {
                        aiContext = it.toInt()
                        Performance.setAiContext(context, aiContext)
                    }
                    Text(stringResource(R.string.perf_context_desc), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option) })
        }
    }
}
