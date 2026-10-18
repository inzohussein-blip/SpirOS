package com.spiros.app.ui.vault

import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.vault.Vault
import com.spiros.app.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(vault: Vault, item: VaultItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    val image by produceState<Bitmap?>(null, item.id) {
        if (item.isImage) {
            value = withContext(Dispatchers.IO) { runCatching { vault.files.loadImage(item.id, 2048) }.getOrNull() }
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(item.mime)) { uri ->
        vault.holdLock = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { vault.files.export(item.id, context.contentResolver, uri) }.isSuccess
            }
            snackbar.showSnackbar(context.getString(if (ok) R.string.vault_exported else R.string.vault_export_failed))
        }
    }

    Scaffold(
        topBar = { SpirTopBar(item.name, onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bitmap = image
                when {
                    bitmap != null -> Image(
                        bitmap.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    item.isImage -> CircularProgressIndicator()
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.vault_no_preview), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.mime, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(
                listOfNotNull(
                    item.size.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(context, it) },
                    DateFormat.getMediumDateFormat(context).format(item.added),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        vault.holdLock = true
                        exporter.launch(item.name)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text(stringResource(R.string.vault_export), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Text(stringResource(R.string.delete), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.vault_delete_title)) },
            text = { Text(stringResource(R.string.vault_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { vault.files.delete(item.id) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
