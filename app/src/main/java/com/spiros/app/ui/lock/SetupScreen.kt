package com.spiros.app.ui.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.spiros.app.R
import com.spiros.app.vault.Vault
import kotlinx.coroutines.launch

/** First run: choose a PIN, confirm it, create the vault. */
@Composable
fun SetupScreen(vault: Vault) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<CharArray?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val pending = first
    if (pending == null) {
        PinEntry(
            title = stringResource(R.string.setup_title),
            subtitle = stringResource(R.string.setup_hint),
            error = if (mismatch) stringResource(R.string.pin_mismatch) else null,
            onSubmit = { first = it; mismatch = false },
        )
    } else {
        PinEntry(
            title = stringResource(R.string.setup_confirm),
            busy = busy,
            onSubmit = { pin ->
                if (pin.contentEquals(pending)) {
                    busy = true
                    scope.launch {
                        vault.setup(pin)
                        pin.fill('0')
                        pending.fill('0')
                    }
                } else {
                    pin.fill('0')
                    pending.fill('0')
                    first = null
                    mismatch = true
                }
            },
            onCancel = { pending.fill('0'); first = null },
        )
    }
}
