package com.spiros.app.ui.lock

import android.app.Activity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import com.spiros.app.R
import com.spiros.app.vault.BiometricGate
import com.spiros.app.vault.Vault
import kotlinx.coroutines.launch

@Composable
fun LockScreen(vault: Vault) {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val biometric = remember { vault.isBiometricEnabled && BiometricGate.isAvailable(context) }
    val promptTitle = stringResource(R.string.biometric_unlock_title)
    val usePin = stringResource(R.string.use_pin)

    fun tryBiometric() {
        val cipher = vault.biometricUnlockCipher() ?: return
        BiometricGate.authenticate(activity, cipher, promptTitle, usePin, onSuccess = { vault.completeBiometricUnlock(it) })
    }

    LaunchedEffect(Unit) { if (biometric) tryBiometric() }

    PinEntry(
        title = stringResource(R.string.lock_title),
        error = error,
        busy = busy,
        onSubmit = { pin ->
            busy = true
            scope.launch {
                val result = vault.unlock(pin)
                pin.fill('0')
                busy = false
                error = when (result) {
                    Vault.UnlockResult.Success -> null
                    Vault.UnlockResult.Wrong -> context.getString(R.string.pin_wrong)
                    is Vault.UnlockResult.LockedOut ->
                        context.getString(R.string.pin_locked_out, (result.remainingMs / 1000 + 1).toInt())
                }
            }
        },
        extraKey = if (biometric) {
            {
                IconButton(onClick = { tryBiometric() }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        Icons.Outlined.Fingerprint,
                        contentDescription = stringResource(R.string.use_fingerprint),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}
