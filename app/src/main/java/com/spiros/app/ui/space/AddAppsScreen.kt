package com.spiros.app.ui.space

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.space.SpaceManager
import com.spiros.app.ui.SpirTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pick apps installed on the phone to clone into the space (same APK, separate data). */
@Composable
fun AddAppsScreen(
    loadCandidates: () -> List<SpaceManager.App>,
    onBack: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    BackHandler(onBack = onBack)
    val candidates by produceState<List<SpaceManager.App>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching(loadCandidates).getOrDefault(emptyList()) }
    }
    val selected = remember { mutableStateListOf<String>() }

    Scaffold(
        topBar = { SpirTopBar(stringResource(R.string.space_add_apps), onBack) },
        floatingActionButton = {
            if (selected.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onConfirm(selected.toList()) },
                    icon = { Icon(Icons.Outlined.Check, contentDescription = null) },
                    text = { Text(stringResource(R.string.space_clone_selected, selected.size)) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val list = candidates
        if (list == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(padding)) {
            Text(
                stringResource(R.string.space_clone_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn {
                items(list, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName, style = MaterialTheme.typography.labelSmall) },
                        leadingContent = { Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp)) },
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                        modifier = Modifier.clickable {
                            if (checked) selected.remove(app.packageName) else selected.add(app.packageName)
                        },
                    )
                }
            }
        }
    }
}
