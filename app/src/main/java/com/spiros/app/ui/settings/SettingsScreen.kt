package com.spiros.app.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import com.spiros.app.ui.theme.Appearance
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Warning
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
import com.spiros.app.security.FlipToPanic
import com.spiros.app.ui.Disguise
import com.spiros.app.ui.ScreenSecurity
import com.spiros.app.ui.SpirTopBar
import com.spiros.app.ui.lock.PinEntry
import com.spiros.app.vault.BiometricGate
import com.spiros.app.vault.Vault
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    vault: Vault,
    onBack: () -> Unit,
    onOpenCompat: () -> Unit,
    onOpenLinuxAccounts: () -> Unit,
    onOpenPerformance: () -> Unit,
) {
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
    var flipPanic by remember { mutableStateOf(FlipToPanic.enabled(context)) }
    var hasSecretCode by remember { mutableStateOf(Disguise.hasSecretCode(context)) }
    var editingCode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun match(vararg ids: Int) = query.isBlank() || ids.any { context.getString(it).contains(query.trim(), ignoreCase = true) }
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.settings_search)) },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (match(R.string.perf_title, R.string.perf_settings_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.perf_title), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(stringResource(R.string.perf_settings_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
                    modifier = Modifier.clickable(onClick = onOpenPerformance),
                    colors = itemColors,
                )
            }
            if (match(R.string.appearance_title, R.string.appearance_theme, R.string.appearance_colors, R.string.appearance_style)) {
                GroupTitle(stringResource(R.string.appearance_title))
                AppearanceSection()
            }
            if (query.isBlank()) GroupTitle(stringResource(R.string.settings_group_unlock))
            if (match(R.string.settings_require_pin, R.string.settings_require_pin_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_require_pin)) },
                    supportingContent = { Text(stringResource(R.string.settings_require_pin_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Pin, contentDescription = null) },
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
            }
            if (match(R.string.settings_biometric, R.string.compat_biometric_missing)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_biometric)) },
                    leadingContent = { Icon(Icons.Rounded.Fingerprint, contentDescription = null) },
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
            }
            if (match(R.string.settings_panic, R.string.settings_panic_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_panic)) },
                    leadingContent = { Icon(Icons.Rounded.Warning, contentDescription = null) },
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
            }
            if (query.isBlank()) GroupTitle(stringResource(R.string.settings_group_privacy))
            if (match(R.string.settings_screenshots, R.string.settings_screenshots_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_screenshots)) },
                    supportingContent = { Text(stringResource(R.string.settings_screenshots_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Screenshot, contentDescription = null) },
                    trailingContent = {
                        Switch(checked = screenshots, onCheckedChange = { on ->
                            ScreenSecurity.setScreenshotsAllowed(context, on)
                            screenshots = on
                        })
                    },
                    colors = itemColors,
                )
            }
            if (disguised && match(R.string.settings_secret_code, R.string.settings_secret_code_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_secret_code)) },
                    supportingContent = { Text(stringResource(R.string.settings_secret_code_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Password, contentDescription = null) },
                    trailingContent = {
                        if (hasSecretCode) {
                            TextButton(onClick = { Disguise.setSecretCode(context, null); hasSecretCode = false }) {
                                Text(stringResource(R.string.remove))
                            }
                        } else {
                            TextButton(onClick = { editingCode = true }) { Text(stringResource(R.string.set)) }
                        }
                    },
                    colors = itemColors,
                )
            }
            if (match(R.string.settings_flip_panic, R.string.settings_flip_panic_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_flip_panic)) },
                    supportingContent = { Text(stringResource(R.string.settings_flip_panic_desc)) },
                    leadingContent = { Icon(Icons.Rounded.ScreenRotation, contentDescription = null) },
                    trailingContent = {
                        Switch(checked = flipPanic, onCheckedChange = { on ->
                            FlipToPanic.setEnabled(context, on)
                            flipPanic = on
                        })
                    },
                    colors = itemColors,
                )
            }
            if (match(R.string.settings_disguise, R.string.settings_disguise_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_disguise)) },
                    leadingContent = { Icon(Icons.Rounded.Calculate, contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.settings_disguise_desc)) },
                    trailingContent = {
                        Switch(checked = disguised, onCheckedChange = { on ->
                            Disguise.setEnabled(context, on)
                            disguised = on
                        })
                    },
                    colors = itemColors,
                )
            }
            run {
                if (query.isBlank()) GroupTitle(stringResource(R.string.dock_linux))
                if (match(R.string.accounts_title, R.string.accounts_settings_desc)) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.accounts_title)) },
                        supportingContent = { Text(stringResource(R.string.accounts_settings_desc)) },
                        leadingContent = { Icon(Icons.Rounded.ManageAccounts, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onOpenLinuxAccounts),
                        colors = itemColors,
                    )
                }
            }
            if (query.isBlank()) GroupTitle(stringResource(R.string.settings_group_device))
            if (match(R.string.compat_title, R.string.compat_subtitle)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.compat_title)) },
                    leadingContent = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                    supportingContent = { Text(stringResource(R.string.compat_subtitle)) },
                    modifier = Modifier.clickable(onClick = onOpenCompat),
                    colors = itemColors,
                )
            }
            if (match(R.string.settings_lock_now)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_lock_now)) },
                    leadingContent = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                    modifier = Modifier.clickable { vault.lock() },
                    colors = itemColors,
                )
            }
            if (query.isBlank()) GroupTitle(stringResource(R.string.settings_group_danger), danger = true)
            if (match(R.string.settings_wipe, R.string.settings_wipe_desc)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_wipe), color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text(stringResource(R.string.settings_wipe_desc)) },
                    modifier = Modifier.clickable { confirmWipe = true },
                    colors = itemColors,
                )
            }
        }
    }

    if (editingCode) {
        var code by remember { mutableStateOf("") }
        val normalized = Disguise.normalizeCode(code)
        AlertDialog(
            onDismissRequest = { editingCode = false },
            title = { Text(stringResource(R.string.settings_secret_code)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_secret_code_help))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        placeholder = { Text("1995×7") },
                        supportingText = { Text(normalized.ifEmpty { "—" }) },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = Disguise.isValidCode(normalized), onClick = {
                    Disguise.setSecretCode(context, normalized)
                    hasSecretCode = true
                    editingCode = false
                }) { Text(stringResource(R.string.set)) }
            },
            dismissButton = { TextButton(onClick = { editingCode = false }) { Text(cancel) } },
        )
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

/** Theme (system/dark/light), colours (SpirOS/Material You) and style (elegant/minimal), applied live. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection() {
    val context = LocalContext.current
    val state by Appearance.state.collectAsState()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            listOf(
                Appearance.Mode.SYSTEM to R.string.appearance_system,
                Appearance.Mode.DARK to R.string.appearance_dark,
                Appearance.Mode.LIGHT to R.string.appearance_light,
            ),
            state.mode,
        ) { mode -> Appearance.update(context) { it.copy(mode = mode) } }

        Text(stringResource(R.string.appearance_colors), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            buildList {
                add(Appearance.Palette.SPIROS to R.string.appearance_spiros)
                if (Build.VERSION.SDK_INT >= 31) add(Appearance.Palette.MATERIAL_YOU to R.string.appearance_material_you)
                add(Appearance.Palette.CYBER_GREEN to R.string.appearance_cyber_green)
                add(Appearance.Palette.CYBER_CYAN to R.string.appearance_cyber_cyan)
            },
            state.palette,
        ) { palette -> Appearance.update(context) { it.copy(palette = palette) } }

        Text(stringResource(R.string.appearance_style), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            listOf(
                Appearance.Style.ELEGANT to R.string.appearance_elegant,
                Appearance.Style.MINIMAL to R.string.appearance_minimal,
            ),
            state.style,
        ) { style -> Appearance.update(context) { it.copy(style = style) } }

        Text(stringResource(R.string.appearance_lock_screen), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            listOf(false to R.string.appearance_lock_normal, true to R.string.appearance_lock_stealth),
            state.stealthLock,
        ) { stealth -> Appearance.update(context) { it.copy(stealthLock = stealth) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(stringResource(label)) })
        }
    }
}
