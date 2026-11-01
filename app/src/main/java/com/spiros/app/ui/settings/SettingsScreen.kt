package com.spiros.app.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.BuildConfig
import com.spiros.app.ui.Disguise
import com.spiros.app.ui.ScreenSecurity
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.ui.lock.PinEntry
import com.spiros.app.vault.BiometricGate
import com.spiros.app.vault.Vault
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vault: Vault, onBack: () -> Unit, onOpenCompat: () -> Unit, onOpenLinuxAccounts: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    val bioAvailable = remember { BiometricGate.isAvailable(context) }
    var bioEnabled by remember { mutableStateOf(vault.isBiometricEnabled) }
    var hasPanic by remember { mutableStateOf(vault.hasPanicPin) }
    var settingPanic by remember { mutableStateOf(false) }
    var panicError by remember { mutableStateOf<String?>(null) }
    var panicBusy by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var disguised by remember { mutableStateOf(Disguise.isEnabled(context)) }
    var lockEnabled by remember { mutableStateOf(vault.isLockEnabled) }
    var confirmNoLock by remember { mutableStateOf(false) }
    var screenshots by remember { mutableStateOf(ScreenSecurity.screenshotsAllowed(context)) }
    val bioTitle = stringResource(R.string.biometric_enable_title)
    val cancel = stringResource(R.string.cancel)

    if (settingPanic) {
        PinEntry(
            title = stringResource(R.string.panic_title),
            subtitle = stringResource(R.string.settings_panic_desc),
            error = panicError,
            busy = panicBusy,
            onSubmit = { pin ->
                panicBusy = true
                scope.launch {
                    val ok = vault.setPanicPin(pin)
                    pin.fill('0')
                    panicBusy = false
                    if (ok) {
                        hasPanic = true
                        settingPanic = false
                        panicError = null
                    } else {
                        panicError = context.getString(R.string.panic_same_as_pin)
                    }
                }
            },
            onCancel = { settingPanic = false; panicError = null },
        )
        return
    }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.settings), onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
            GroupTitle(stringResource(R.string.settings_group_unlock))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_require_pin)) },
                supportingContent = { Text(stringResource(R.string.settings_require_pin_desc)) },
                leadingContent = { Icon(Icons.Outlined.Pin, contentDescription = null) },
                trailingContent = {
                    Switch(checked = lockEnabled, onCheckedChange = { on ->
                        if (on) {
                            vault.setLockEnabled(true)
                            lockEnabled = true
                        } else {
                            confirmNoLock = true
                        }
                    })
                },
                colors = itemColors,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_biometric)) },
                leadingContent = { Icon(Icons.Outlined.Fingerprint, contentDescription = null) },
                supportingContent = {
                    if (!bioAvailable) Text(stringResource(R.string.compat_biometric_missing))
                },
                trailingContent = {
                    Switch(
                        checked = bioEnabled,
                        enabled = bioAvailable,
                        onCheckedChange = { on ->
                            if (on) {
                                val cipher = runCatching { vault.biometricEnrollCipher() }.getOrNull()
                                if (cipher != null) {
                                    BiometricGate.authenticate(activity, cipher, bioTitle, cancel, onSuccess = {
                                        bioEnabled = runCatching { vault.completeBiometricEnroll(it) }.isSuccess
                                    })
                                }
                            } else {
                                vault.disableBiometric()
                                bioEnabled = false
                            }
                        },
                    )
                },
                colors = itemColors,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_panic)) },
                leadingContent = { Icon(Icons.Outlined.Warning, contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.settings_panic_desc)) },
                trailingContent = {
                    if (hasPanic) {
                        TextButton(onClick = { vault.clearPanicPin(); hasPanic = false }) {
                            Text(stringResource(R.string.remove))
                        }
                    } else {
                        TextButton(onClick = { settingPanic = true }) { Text(stringResource(R.string.set)) }
                    }
                },
                colors = itemColors,
            )
            GroupTitle(stringResource(R.string.settings_group_privacy))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_screenshots)) },
                supportingContent = { Text(stringResource(R.string.settings_screenshots_desc)) },
                leadingContent = { Icon(Icons.Outlined.Screenshot, contentDescription = null) },
                trailingContent = {
                    Switch(checked = screenshots, onCheckedChange = { on ->
                        ScreenSecurity.setScreenshotsAllowed(context, on)
                        screenshots = on
                    })
                },
                colors = itemColors,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_disguise)) },
                leadingContent = { Icon(Icons.Outlined.Calculate, contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.settings_disguise_desc)) },
                trailingContent = {
                    Switch(checked = disguised, onCheckedChange = { on ->
                        Disguise.setEnabled(context, on)
                        disguised = on
                    })
                },
                colors = itemColors,
            )
            if (BuildConfig.FLAVOR == "speed") {
                GroupTitle(stringResource(R.string.dock_linux))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.accounts_title)) },
                    supportingContent = { Text(stringResource(R.string.accounts_settings_desc)) },
                    leadingContent = { Icon(Icons.Outlined.ManageAccounts, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenLinuxAccounts),
                    colors = itemColors,
                )
            }
            GroupTitle(stringResource(R.string.settings_group_device))
            ListItem(
                headlineContent = { Text(stringResource(R.string.compat_title)) },
                leadingContent = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.compat_subtitle)) },
                modifier = Modifier.clickable(onClick = onOpenCompat),
                colors = itemColors,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_lock_now)) },
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                modifier = Modifier.clickable { vault.lock() },
                colors = itemColors,
            )
            GroupTitle(stringResource(R.string.settings_group_danger), danger = true)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_wipe), color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                supportingContent = { Text(stringResource(R.string.settings_wipe_desc)) },
                modifier = Modifier.clickable { confirmWipe = true },
                colors = itemColors,
            )
        }
    }

    if (confirmNoLock) {
        AlertDialog(
            onDismissRequest = { confirmNoLock = false },
            title = { Text(stringResource(R.string.settings_no_lock_title)) },
            text = { Text(stringResource(R.string.settings_no_lock_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmNoLock = false
                    vault.setLockEnabled(false)
                    lockEnabled = false
                }) { Text(stringResource(R.string.settings_no_lock_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmNoLock = false }) { Text(cancel) } },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.settings_wipe)) },
            text = { Text(stringResource(R.string.settings_wipe_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; vault.wipe() }) {
                    Text(stringResource(R.string.settings_wipe), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(cancel) } },
        )
    }
}

@Composable
private fun GroupTitle(text: String, danger: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
