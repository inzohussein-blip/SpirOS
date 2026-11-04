package com.spiros.app.ui.linux

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.remote.Engine
import com.spiros.app.remote.LinuxApps
import com.spiros.app.remote.LinuxStore
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.ui.common.TaskCard
import com.spiros.app.ui.home.storeTaskTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The SpirOS Linux app store: curated apps by category, search over all of Ubuntu,
 * install / open / remove, and updates. Installs run in the engine's background.
 */
@Composable
fun StoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Engine.status.collectAsState()
    val installed by LinuxStore.installed.collectAsState()
    val catalog = remember { LinuxStore.catalog(context) }
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<LinuxStore.Found>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var updates by remember { mutableStateOf<List<String>?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<Pair<String, String>?>(null) }
    val task = status.task?.takeIf { it.kind == "apt" || it.kind == "addon" }
    val published by LinuxStore.published.collectAsState()
    LaunchedEffect(Unit) { LinuxStore.refreshPublished(force = true) }
    // Installs queue up in the engine, so buttons stay usable while one runs.
    val busy = false
    var tab by remember { mutableStateOf(0) }
    val downloads = status.queue.size + if (status.task?.let { it.active && (it.kind == "apt" || it.kind == "addon") } == true) 1 else 0

    // What is installed; again whenever an app store task ends.
    LaunchedEffect(status.connected, task?.active) {
        if (status.connected && task?.active != true) LinuxStore.refresh(context, found.map { it.name })
    }
    // Search all of Ubuntu, after the user stops typing.
    LaunchedEffect(query) {
        found = emptyList()
        if (query.trim().length < 2) return@LaunchedEffect
        delay(500)
        searching = true
        found = LinuxStore.search(context, query)
        searching = false
        LinuxStore.refresh(context, found.map { it.name })
    }

    Scaffold(topBar = { SpirTopBar(stringResource(R.string.store_title), onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!status.linuxInstalled) {
                item { Text(stringResource(R.string.store_need_linux), style = MaterialTheme.typography.bodyLarge) }
                return@LazyColumn
            }
            item {
                androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab) {
                    androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.store_tab_apps)) })
                    androidx.compose.material3.Tab(
                        selected = tab == 1, onClick = { tab = 1 },
                        text = { Text(stringResource(R.string.store_tab_downloads) + if (downloads > 0) " ($downloads)" else "") },
                    )
                }
            }
            if (tab == 1) {
                item { DownloadsList { Text(stringResource(R.string.download_none), style = MaterialTheme.typography.bodyLarge) } }
                return@LazyColumn
            }
            // Jobs with no item of their own here: Linux updates, a failed job.
            task?.takeIf { (it.active && it.kind == "apt" && !it.name.startsWith("install ")) || (!it.active && it.error != null && it.error != "cancelled") }?.let { t ->
                item { TaskCard(t, title = if (t.kind == "addon") com.spiros.app.ui.home.addonTaskTitle(t) else storeTaskTitle(t)) }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    placeholder = { Text(stringResource(R.string.store_search)) },
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.trim().length >= 2) {
                if (!searching && found.isEmpty()) item { Text(stringResource(R.string.store_no_results)) }
                items(found, key = { "f-" + it.name }) { f ->
                    PackageRow(
                        title = f.name, description = f.description, icon = R.drawable.ic_store_tools,
                        installed = f.name in installed, busy = busy,
                        onInstall = { LinuxStore.install(context, f.name) },
                        onRemove = { confirmRemove = f.name to f.name },
                        onOpen = null,
                    )
                }
                return@LazyColumn
            }
            // Updates for everything installed in Linux.
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(stringResource(R.string.store_updates), style = MaterialTheme.typography.titleSmall)
                            Text(
                                when {
                                    checkingUpdates -> stringResource(R.string.update_checking)
                                    updates == null -> stringResource(R.string.store_updates_hint)
                                    updates!!.isEmpty() -> stringResource(R.string.store_up_to_date)
                                    else -> stringResource(R.string.store_updates_n, updates!!.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            checkingUpdates -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            !updates.isNullOrEmpty() -> FilledTonalButton(enabled = !busy, onClick = {
                                LinuxStore.upgradeAll(context); updates = null
                            }) { Text(stringResource(R.string.store_update_all)) }
                            else -> TextButton(enabled = !busy, onClick = {
                                scope.launch { checkingUpdates = true; updates = LinuxStore.upgradable(context); checkingUpdates = false }
                            }) { Text(stringResource(R.string.update_check)) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text(stringResource(R.string.store_cat_all)) })
                    LinuxStore.CATEGORIES.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(stringResource(LinuxStore.categoryLabel(c))) })
                    }
                }
            }
            // MetaTrader 5 / 4 full: Windows programs through Hangover, with updates.
            if (category == null || category == "trading") {
                listOf("mt5" to "MetaTrader 5", "mt4" to "MetaTrader 4").forEach { (id, name) ->
                    item(key = "addon-$id") {
                        val isInstalled = status.addonInstalled(id)
                        val newer = id in LinuxStore.addonUpdates(status, published)
                        val available = published[id] != null
                        val size = LinuxStore.addonDownloadSize(status, published, id)
                        val lApp = LinuxApps.byId(id)
                        PackageRow(
                            title = name,
                            description = stringResource(if (status.arch == "arm64") R.string.store_mt_desc else R.string.store_mt_arm_only) +
                                if (size > 0 && (!isInstalled || newer)) " · " + megabytes(size) else "",
                            icon = if (id == "mt4") R.drawable.ic_sc_mt4 else R.drawable.ic_sc_mt5,
                            installed = isInstalled, busy = status.arch != "arm64" || !available,
                            task = lApp?.let { appTask(status, it) },
                            queued = lApp?.let { appQueued(status, it) } == true,
                            onCancelQueue = { Engine.dequeue(context, "addon", id) },
                            onInstall = { LinuxApps.byId(id)?.let { LinuxApps.launch(context, it) } },
                            onRemove = { confirmRemove = name to "addon:$id" },
                            onOpen = { LinuxApps.byId(id)?.let { LinuxApps.launch(context, it) } },
                            onPin = { LinuxApps.byId(id)?.let { LinuxApps.pinToHomeScreen(context, it) } },
                            onUpdate = if (newer) {
                                { LinuxStore.ask(LinuxStore.Confirm(name, size) { Engine.installAddon(context, id) }) }
                            } else null,
                        )
                    }
                }
            }
            items(catalog.filter { category == null || it.category == category }, key = { it.id }) { app ->
                val isInstalled = app.main in installed
                PackageRow(
                    title = app.name,
                    description = app.description + if (app.sizeMb > 0) " · ~${app.sizeMb} MB" else "",
                    icon = LinuxStore.categoryIcon(app.category),
                    installed = isInstalled, busy = busy,
                    task = status.task?.takeIf { it.active && it.kind == "apt" && it.name == "install ${app.packages}" },
                    queued = ("apt" to "install ${app.packages}") in status.queue,
                    onCancelQueue = { Engine.dequeue(context, "apt", "install ${app.packages}") },
                    onInstall = {
                        // Big apps: show size and free space first.
                        if (app.sizeMb >= 50) LinuxStore.ask(LinuxStore.Confirm(app.name, app.sizeMb * 1048576L) { LinuxStore.install(context, app.packages) })
                        else LinuxStore.install(context, app.packages)
                    },
                    onRemove = { confirmRemove = app.name to app.packages },
                    onOpen = if (app.exec.isNotBlank()) {
                        {
                            LinuxApps.byId(context, "store:${app.id}")?.let { LinuxApps.launch(context, it) }
                        }
                    } else null,
                    onPin = if (app.exec.isNotBlank()) {
                        { LinuxApps.byId(context, "store:${app.id}")?.let { LinuxApps.pinToHomeScreen(context, it) } }
                    } else null,
                )
            }
            item {
                Text(
                    stringResource(R.string.store_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmRemove?.let { (name, packages) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.store_remove_title, name)) },
            text = { Text(stringResource(R.string.store_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    if (packages.startsWith("addon:")) Engine.removeAddon(context, packages.removePrefix("addon:"))
                    else LinuxStore.remove(context, packages)
                    confirmRemove = null
                }) {
                    Text(stringResource(R.string.store_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PackageRow(
    title: String,
    description: String,
    icon: Int,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onOpen: (() -> Unit)?,
    onPin: (() -> Unit)? = null,
    onUpdate: (() -> Unit)? = null,
    task: Engine.Task? = null,
    queued: Boolean = false,
    onCancelQueue: () -> Unit = {},
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(icon), null, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            // Downloading: the progress replaces the buttons (pause / cancel included).
            if (task != null) {
                com.spiros.app.ui.common.TaskProgress(task, hint = false)
                return@Column
            }
            if (queued) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.download_queued), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelQueue) { Text(stringResource(R.string.cancel)) }
                }
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed) {
                    Button(enabled = !busy, onClick = onInstall) { Text(stringResource(R.string.store_install)) }
                } else {
                    onUpdate?.let { FilledTonalButton(enabled = !busy, onClick = it) { Text(stringResource(R.string.store_update)) } }
                    onOpen?.let { Button(onClick = it) { Text(stringResource(R.string.store_open)) } }
                    onPin?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.store_pin)) } }
                    TextButton(enabled = !busy, onClick = onRemove) {
                        Text(stringResource(R.string.store_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
