package com.spiros.app.ui.performance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spiros.app.R
import com.spiros.app.SpirApp
import com.spiros.app.perf.BatteryExemption
import com.spiros.app.remote.Engine

/**
 * "Run without battery limits" for SpirOS and its engine (Linux, AI, MT5 run there).
 * [onlyWhenNeeded]: show nothing once both are allowed.
 */
@Composable
fun BatteryCard(onlyWhenNeeded: Boolean = false) {
    val context = LocalContext.current
    val status by Engine.status.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; Engine.refresh() }
    val spiros = remember(tick) { BatteryExemption.spirosExempt(context) }
    val engine = !status.connected || status.batteryExempt
    if (onlyWhenNeeded && spiros && engine) return
    val vault = (context.applicationContext as SpirApp).vault
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (spiros && engine) Icons.Rounded.CheckCircle else Icons.Rounded.BatteryAlert, null,
                    tint = if (spiros && engine) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            Text(stringResource(R.string.battery_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!spiros) FilledTonalButton(onClick = { vault.holdLock = true; BatteryExemption.requestSpiros(context) }) { Text(stringResource(R.string.battery_allow_spiros)) }
                if (!engine) FilledTonalButton(onClick = { vault.holdLock = true; BatteryExemption.requestEngine(context) }) { Text(stringResource(R.string.battery_allow_engine)) }
                if (spiros && engine) Text(stringResource(R.string.battery_ok), color = MaterialTheme.colorScheme.tertiary)
            }
            if (BatteryExemption.isHonor) {
                Text(stringResource(R.string.battery_honor), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vault.holdLock = true; BatteryExemption.openSettings(context) }) { Text(stringResource(R.string.battery_open_settings)) }
            }
        }
    }
}
