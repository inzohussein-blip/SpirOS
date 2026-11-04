package com.spiros.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.SpirApp
import com.spiros.app.backup.Backup
import com.spiros.app.backup.ProblemReport
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Encrypted backup / restore and the problem report, in Settings. */
@Composable
fun BackupSection(itemColors: ListItemColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = (context.applicationContext as SpirApp).vault
    var mode by remember { mutableStateOf<String?>(null) } // "create" | "restore"
    var target by remember { mutableStateOf<Uri?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vault.holdLock = false
        if (uri != null) { target = uri; mode = "create" }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vault.holdLock = false
        if (uri != null) { target = uri; mode = "restore" }
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.backup_create)) },
        supportingContent = { Text(stringResource(R.string.backup_create_desc)) },
        leadingContent = { Icon(Icons.Rounded.CloudUpload, null) },
        modifier = Modifier.clickable {
            vault.holdLock = true // the file picker must not lock SpirOS
            createDoc.launch("SpirOS-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".spirosbak")
        },
        colors = itemColors,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.backup_restore)) },
        supportingContent = { Text(stringResource(R.string.backup_restore_desc)) },
        leadingContent = { Icon(Icons.Rounded.Restore, null) },
        modifier = Modifier.clickable { vault.holdLock = true; openDoc.launch(arrayOf("*/*")) },
        colors = itemColors,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.report_title)) },
        supportingContent = { Text(stringResource(if (reporting) R.string.report_collecting else R.string.report_desc)) },
        leadingContent = { Icon(Icons.Rounded.BugReport, null) },
        modifier = Modifier.clickable(enabled = !reporting) {
            reporting = true
            scope.launch {
                val report = ProblemReport.build(context)
                reporting = false
                ProblemReport.share(context, report)
            }
        },
        colors = itemColors,
    )

    // Password for the backup (twice when creating).
    val m = mode
    val uri = target
    if (m != null && uri != null && progress == null) {
        var password by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        val ok = password.length >= 8 && (m == "restore" || password == again)
        AlertDialog(
            onDismissRequest = { mode = null },
            title = { Text(stringResource(if (m == "create") R.string.backup_create else R.string.backup_restore)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (m == "create") R.string.backup_password_help else R.string.backup_password_enter))
                    OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(R.string.backup_password)) }, modifier = Modifier.fillMaxWidth())
                    if (m == "create") OutlinedTextField(again, { again = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(R.string.backup_password_again)) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    val pw = password.toCharArray()
                    mode = null
                    progress = ""
                    scope.launch {
                        result = try {
                            val r = if (m == "create") Backup.create(context, vault.files, uri, pw) { progress = it }
                            else Backup.restore(context, vault.files, uri, pw) { progress = it }
                            context.getString(
                                if (m == "create") R.string.backup_done else R.string.restore_done,
                                r.vaultFiles, r.records, r.settings, r.tradingFiles,
                            )
                        } catch (e: Backup.WrongPassword) {
                            context.getString(R.string.backup_wrong_password)
                        } catch (e: Exception) {
                            context.getString(R.string.backup_failed, e.message ?: e.toString())
                        } finally {
                            pw.fill('\u0000')
                            progress = null
                        }
                    }
                }) { Text(stringResource(R.string.set)) }
            },
            dismissButton = { TextButton(onClick = { mode = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    progress?.let {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.backup_working)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(it) } },
            confirmButton = {},
        )
    }
    result?.let {
        AlertDialog(
            onDismissRequest = { result = null },
            text = { Text(it, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { TextButton(onClick = { result = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
