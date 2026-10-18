package com.spiros.app.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spiros.app.R

const val PIN_MIN = 6
const val PIN_MAX = 12

/**
 * Full-screen PIN entry with its own keypad. The system keyboard is never used,
 * so third-party keyboard apps cannot see the PIN.
 */
@Composable
fun PinEntry(
    title: String,
    subtitle: String? = null,
    error: String? = null,
    busy: Boolean = false,
    onSubmit: (CharArray) -> Unit,
    extraKey: (@Composable () -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val digits = remember { mutableStateListOf<Char>() }

    fun submit() {
        if (digits.size < PIN_MIN || busy) return
        val pin = digits.toCharArray()
        digits.clear()
        onSubmit(pin)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(16.dp)) {
                repeat(maxOf(PIN_MIN, digits.size)) { i ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < digits.size) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(24.dp))

            // Digits keep phone-keypad order even in right-to-left languages.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("123", "456", "789").forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            row.forEach { d -> DigitKey(d) { if (digits.size < PIN_MAX && !busy) digits.add(d) } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) { extraKey?.invoke() }
                        DigitKey('0') { if (digits.size < PIN_MAX && !busy) digits.add('0') }
                        Box(Modifier.size(KEY_SIZE), contentAlignment = Alignment.Center) {
                            IconButton(onClick = { if (digits.isNotEmpty()) digits.removeAt(digits.lastIndex) }) {
                                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = stringResource(R.string.action_backspace))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onCancel != null) {
                    FilledTonalButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                FilledIconButton(
                    onClick = { submit() },
                    enabled = digits.size >= PIN_MIN && !busy,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.action_confirm))
                }
            }
        }
    }
}

private val KEY_SIZE = 72.dp

@Composable
private fun DigitKey(digit: Char, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(KEY_SIZE),
    ) {
        Text(digit.toString(), fontSize = 26.sp)
    }
}
