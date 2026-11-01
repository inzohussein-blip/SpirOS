package com.spiros.app.ui.linux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.linux.LinuxAccounts
import com.spiros.app.remote.Engine
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import com.spiros.app.ui.SpirTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Linux accounts without the terminal: add, passwords (incl. root), admin, delete, desktop user. */
@Composable
fun LinuxAccountsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var passwordFor by remember { mutableStateOf<String?>(null) }
    var deleteUser by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newAdmin by remember { mutableStateOf(true) }

    val status by Engine.status.collectAsState()
    val users by produceState(emptyList<Engine.User>(), refresh, status.connected) {
        value = Engine.parseUsers(Engine.call(context) { it.users() })
    }
    var desktopUser by remember { mutableStateOf("") }
    LaunchedEffect(refresh, status.connected) { desktopUser = Engine.call(context) { it.desktopUser() }.orEmpty() }

    /** Runs an account action in the engine (as root inside Linux). */
    fun act(done: Int, action: String, name: String, password: String = "", admin: Boolean = false) {
        busy = true
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                Engine.call(context) { it.account(action, name, password, admin) ?: "" } ?: "engine not connected"
            }
            val result = if (error.isEmpty()) Result.success(Unit) else Result.failure(Exception(error))
            busy = false
            refresh++
            snackbar.showSnackbar(
                result.fold({ context.getString(done) }, { context.getString(R.string.accounts_failed, it.message ?: "") }),
            )
        }
    }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.accounts_title), onBack) },
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
            if (!status.connected || !status.linuxInstalled) {
                Text(stringResource(R.string.accounts_need_linux))
                return@Column
            }
            Text(stringResource(R.string.accounts_intro), style = MaterialTheme.typography.bodySmall)

            users.forEach { user ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (user.admin) Icons.Rounded.AdminPanelSettings else Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(user.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(if (user.admin) R.string.accounts_admin else R.string.accounts_standard),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RadioButton(
                                selected = desktopUser == user.name,
                                onClick = {
                                    val name = user.name
                                    Engine.send(context) { it.setDesktopUser(name) }
                                    desktopUser = name
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { passwordFor = user.name }, enabled = !busy) {
                                Text(stringResource(R.string.accounts_change_password))
                            }
                            if (user.uid != 0) {
                                TextButton(
                                    onClick = {
                                        act(R.string.accounts_saved, "admin", user.name, admin = !user.admin)
                                    },
                                    enabled = !busy,
                                ) {
                                    Text(stringResource(if (user.admin) R.string.accounts_remove_admin else R.string.accounts_make_admin))
                                }
                                TextButton(onClick = { deleteUser = user.name }, enabled = !busy) {
                                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.accounts_desktop_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.accounts_add), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.lowercase().trim() },
                        label = { Text(stringResource(R.string.accounts_username)) },
                        singleLine = true,
                        isError = newName.isNotEmpty() && !LinuxAccounts.isValidName(newName),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordField(newPassword, { newPassword = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newAdmin, onCheckedChange = { newAdmin = it })
                        Text(stringResource(R.string.accounts_admin_checkbox))
                    }
                    Button(
                        enabled = !busy && LinuxAccounts.isValidName(newName) && LinuxAccounts.isValidPassword(newPassword),
                        onClick = {
                            val name = newName
                            val password = newPassword
                            val admin = newAdmin
                            newName = ""
                            newPassword = ""
                            act(R.string.accounts_added, "add", name, password, admin)
                        },
                    ) { Text(stringResource(R.string.accounts_add)) }
                }
            }
        }
    }

    passwordFor?.let { name ->
        var password by remember(name) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordFor = null },
            title = { Text(stringResource(R.string.accounts_password_for, name)) },
            text = { PasswordField(password, { password = it }) },
            confirmButton = {
                TextButton(
                    enabled = LinuxAccounts.isValidPassword(password),
                    onClick = {
                        passwordFor = null
                        act(R.string.accounts_saved, "password", name, password)
                    },
                ) { Text(stringResource(R.string.set)) }
            },
            dismissButton = { OutlinedButton(onClick = { passwordFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleteUser?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteUser = null },
            title = { Text(stringResource(R.string.accounts_delete_title, name)) },
            text = { Text(stringResource(R.string.accounts_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteUser = null
                    act(R.string.accounts_deleted, "delete", name)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { OutlinedButton(onClick = { deleteUser = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.accounts_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        supportingText = { Text(stringResource(R.string.accounts_password_rule)) },
        modifier = Modifier.fillMaxWidth(),
    )
}
