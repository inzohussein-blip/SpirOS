package com.spiros.app.ui.vault

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.perf.Performance
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.ui.common.ConfirmSheet
import com.spiros.app.ui.common.EmptyState
import com.spiros.app.ui.common.Haptics
import com.spiros.app.ui.common.Skeleton
import com.spiros.app.vault.Vault
import com.spiros.app.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Filters at the top of the vault. */
private enum class Kind(val label: Int) { ALL(R.string.vault_all), PHOTOS(R.string.vault_photos), VIDEOS(R.string.vault_videos), FILES(R.string.vault_files) }

private fun VaultItem.kind() = when {
    isImage -> Kind.PHOTOS
    isVideo -> Kind.VIDEOS
    else -> Kind.FILES
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(vault: Vault, onBack: () -> Unit, onOpen: (VaultItem) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var items by remember { mutableStateOf<List<VaultItem>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var deleteOriginals by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(Kind.ALL) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(Performance.vaultGrid(context)) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    LaunchedEffect(refresh) {
        items = withContext(Dispatchers.IO) { runCatching { vault.files.list() }.getOrDefault(emptyList()) }
    }
    BackHandler(enabled = selecting || searching) {
        if (selecting) selected = emptySet() else { searching = false; query = "" }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vault.holdLock = false
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            var ok = 0
            var removed = 0
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    runCatching { vault.files.import(context.contentResolver, uri, deleteOriginals) }
                        .onSuccess { deleted -> ok++; if (deleted) removed++ }
                }
            }
            importing = false
            refresh++
            if (ok == uris.size) Haptics.success(view) else Haptics.error(view)
            snackbar.showSnackbar(context.getString(R.string.vault_imported, ok, uris.size, removed))
        }
    }

    // Export the selection into a folder the user picks.
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        vault.holdLock = false
        tree ?: return@rememberLauncherForActivityResult
        val chosen = items.orEmpty().filter { it.id in selected }
        selected = emptySet()
        importing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                chosen.count { item ->
                    runCatching {
                        val doc = DocumentsContract.createDocument(context.contentResolver, parent, item.mime, item.name)!!
                        vault.files.export(item.id, context.contentResolver, doc)
                    }.isSuccess
                }
            }
            importing = false
            Haptics.success(view)
            snackbar.showSnackbar(context.getString(R.string.vault_exported_n, ok, chosen.size))
        }
    }

    val all = items
    val shown = all?.filter { (kind == Kind.ALL || it.kind() == kind) && (query.isBlank() || it.name.contains(query, ignoreCase = true)) }

    Scaffold(
        topBar = {
            when {
                selecting -> SpirTopBar(
                    stringResource(R.string.vault_selected, selected.size),
                    onBack = { selected = emptySet() },
                ) {
                    IconButton(onClick = { selected = shown.orEmpty().map { it.id }.toSet() }) {
                        Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.vault_select_all))
                    }
                }
                else -> SpirTopBar(stringResource(R.string.section_vault), onBack) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, contentDescription = stringResource(R.string.vault_search))
                    }
                    IconButton(onClick = { grid = !grid; Performance.setVaultGrid(context, grid) }) {
                        Icon(
                            if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                            contentDescription = stringResource(if (grid) R.string.vault_list_view else R.string.vault_grid_view),
                        )
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(selecting, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                BottomAppBar {
                    TextButton(
                        onClick = { vault.holdLock = true; folder.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.FileDownload, null)
                        Text(stringResource(R.string.vault_export), modifier = Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!importing && !selecting) {
                ExtendedFloatingActionButton(
                    onClick = {
                        vault.holdLock = true
                        picker.launch(arrayOf("*/*"))
                    },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.vault_import)) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text(stringResource(R.string.vault_search_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            ) {
                Kind.entries.forEach { k ->
                    val count = all?.count { k == Kind.ALL || it.kind() == k } ?: 0
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = k },
                        label = { Text(stringResource(k.label) + if (count > 0) "  $count" else "") },
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { deleteOriginals = !deleteOriginals }
                    .padding(horizontal = 8.dp),
            ) {
                Checkbox(checked = deleteOriginals, onCheckedChange = { deleteOriginals = it })
                Text(stringResource(R.string.vault_delete_originals), style = MaterialTheme.typography.bodySmall)
            }

            fun toggle(item: VaultItem) {
                selected = if (item.id in selected) selected - item.id else selected + item.id
            }
            fun click(item: VaultItem) = if (selecting) toggle(item) else onOpen(item)
            fun longClick(item: VaultItem) {
                Haptics.tick(view)
                toggle(item)
            }

            when {
                // Loading: placeholders shaped like the content.
                shown == null -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    userScrollEnabled = false,
                ) {
                    items(12) { Skeleton(Modifier.fillMaxWidth().aspectRatio(0.85f)) }
                }
                all.isNullOrEmpty() -> EmptyState(
                    Icons.Rounded.Lock,
                    stringResource(R.string.vault_empty_title),
                    stringResource(R.string.vault_empty),
                )
                shown.isEmpty() -> EmptyState(Icons.Rounded.Search, stringResource(R.string.vault_no_match))
                grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { item ->
                        VaultTile(
                            vault, item, item.id in selected,
                            Modifier.combinedClickable(onClick = { click(item) }, onLongClick = { longClick(item) }),
                        )
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(shown, key = { it.id }) { item ->
                        ListItem(
                            leadingContent = { Thumb(vault, item, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))) },
                            headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    android.text.format.Formatter.formatShortFileSize(context, item.size) + " · " +
                                        android.text.format.DateUtils.getRelativeTimeSpanString(item.added),
                                )
                            },
                            trailingContent = {
                                if (item.id in selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.combinedClickable(onClick = { click(item) }, onLongClick = { longClick(item) }),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val chosen = items.orEmpty().filter { it.id in selected }
        ConfirmSheet(
            title = stringResource(R.string.vault_delete_many, chosen.size),
            lost = stringResource(R.string.vault_delete_lost),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                selected = emptySet()
                scope.launch {
                    withContext(Dispatchers.IO) { chosen.forEach { runCatching { vault.files.delete(it.id) } } }
                    Haptics.success(view)
                    refresh++
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Thumb(vault: Vault, item: VaultItem, modifier: Modifier) {
    val thumb by produceState<android.graphics.Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { vault.files.thumbnail(item.id) }.getOrNull() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun VaultTile(vault: Vault, item: VaultItem, checked: Boolean, modifier: Modifier) {
    Card(
        modifier = modifier.then(
            if (checked) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier,
        ),
    ) {
        Box {
            Thumb(vault, item, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
            if (checked) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
        Text(
            item.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(6.dp),
        )
    }
}
