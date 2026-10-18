package com.spiros.app.ui.compat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.compat.DeviceCompat
import com.spiros.app.ui.SpirTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CompatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val report by produceState<DeviceCompat.Report?>(null) {
        value = withContext(Dispatchers.IO) { DeviceCompat.run(context) }
    }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.compat_title), onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val r = report
        if (r == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(r.checks) { CheckCard(it) }
        }
    }
}

@Composable
private fun CheckCard(check: DeviceCompat.Check) {
    val (icon, tint) = when (check.level) {
        DeviceCompat.Level.OK -> Icons.Outlined.CheckCircle to MaterialTheme.colorScheme.secondary
        DeviceCompat.Level.WARN -> Icons.Outlined.WarningAmber to Color(0xFFFFB74D)
        DeviceCompat.Level.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(check.title), style = MaterialTheme.typography.titleSmall)
                Text(check.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                check.fallback?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
        }
    }
}
