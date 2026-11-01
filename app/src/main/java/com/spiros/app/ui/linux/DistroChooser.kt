package com.spiros.app.ui.linux

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.SpirApp
import com.spiros.app.ai.AiModels

/** A system the user can install. */
private data class Choice(val id: String, val title: Int, val desc: Int, val sizeGb: String, val minRamGb: Int)

private val CHOICES = listOf(
    Choice("spiros", R.string.distro_spiros, R.string.distro_spiros_desc, "0.2", 3),
    Choice("xfce", R.string.distro_xfce, R.string.distro_xfce_desc, "0.22", 3),
    Choice("mate", R.string.distro_mate, R.string.distro_mate_desc, "0.3", 4),
    Choice("cinnamon", R.string.distro_cinnamon, R.string.distro_cinnamon_desc, "0.3", 8),
    Choice("custom", R.string.distro_custom, R.string.distro_custom_desc, "", 0),
)

/** Human name of an installed system id. */
@Composable
fun distroName(id: String) = when (id) {
    "spiros" -> stringResource(R.string.distro_spiros)
    "xfce" -> stringResource(R.string.distro_xfce)
    "mate" -> stringResource(R.string.distro_mate)
    "cinnamon" -> stringResource(R.string.distro_cinnamon)
    "custom" -> stringResource(R.string.distro_custom)
    else -> "Debian + Xfce"
}

/**
 * Pick the Linux system: Mint Xfce (default), Mint MATE, Cinnamon, or your own root
 * filesystem from an https link or a file on the phone.
 */
@Composable
fun DistroChooser(
    current: String,
    onInstall: (desktop: String, url: String?, sha256: String?, file: Uri?) -> Unit,
    onCancel: (() -> Unit)?,
) {
    val context = LocalContext.current
    val ram = remember { AiModels.phoneRamGb(context) }
    var selected by remember { mutableStateOf("spiros") }
    var fromFile by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { file = it }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.distro_choose), style = MaterialTheme.typography.titleMedium)
            CHOICES.forEach { c ->
                val tooHeavy = c.minRamGb > ram
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected == c.id) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().clickable { selected = c.id },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == c.id, onClick = { selected = c.id })
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(c.title) + if (c.id == "spiros") "  · " + stringResource(R.string.distro_recommended) else "",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(stringResource(c.desc), style = MaterialTheme.typography.bodySmall)
                            if (c.sizeGb.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.distro_size, c.sizeGb, c.minRamGb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (c.id == "cinnamon" || tooHeavy) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                                    Text(
                                        stringResource(if (tooHeavy) R.string.distro_ram_warning else R.string.distro_cinnamon_warning, ram),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selected == "custom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !fromFile, onClick = { fromFile = false }, label = { Text(stringResource(R.string.distro_from_link)) })
                    FilterChip(selected = fromFile, onClick = { fromFile = true }, label = { Text(stringResource(R.string.distro_from_file)) })
                }
                if (fromFile) {
                    OutlinedButton(onClick = {
                        // The system picker must not lock SpirOS (that would lose the result).
                        (context.applicationContext as SpirApp).vault.holdLock = true
                        picker.launch(arrayOf("*/*"))
                    }) {
                        Icon(Icons.Rounded.FolderOpen, null)
                        Text(
                            file?.lastPathSegment?.substringAfterLast('/') ?: stringResource(R.string.distro_pick_file),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        label = { Text(stringResource(R.string.distro_url)) },
                        singleLine = true,
                        isError = url.isNotEmpty() && !url.startsWith("https://"),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = sha,
                    onValueChange = { sha = it.trim() },
                    label = { Text(stringResource(R.string.distro_sha)) },
                    singleLine = true,
                    isError = sha.isNotEmpty() && !sha.matches(Regex("^[0-9a-fA-F]{64}$")),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.distro_custom_help), style = MaterialTheme.typography.labelSmall)
            }

            if (current.isNotEmpty()) {
                Text(
                    stringResource(R.string.distro_change_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val customOk = if (fromFile) file != null else url.startsWith("https://")
            val shaOk = sha.isEmpty() || sha.matches(Regex("^[0-9a-fA-F]{64}$"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = selected != "custom" || (customOk && shaOk),
                    onClick = {
                        if (selected == "custom") {
                            onInstall("custom", if (fromFile) null else url, sha.ifEmpty { null }, if (fromFile) file else null)
                        } else {
                            onInstall(selected, null, null, null)
                        }
                    },
                ) { Text(stringResource(R.string.distro_install)) }
                if (onCancel != null) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
