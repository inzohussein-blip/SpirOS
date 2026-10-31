package com.spiros.app.ui.home

import android.os.UserHandle
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spiros.app.R
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.theme.SpirAccent
import com.spiros.app.ui.theme.SpirNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

private data class Tool(val icon: ImageVector, @StringRes val label: Int, val onClick: () -> Unit)

private data class Desktop(val user: UserHandle?, val apps: List<SpaceManager.App>)

/**
 * The SpirOS "second interface": a launcher-style home showing only the isolated
 * space's apps, with SpirOS's own tools in the dock.
 */
@Composable
fun HomeScreen(
    onOpenVault: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenShield: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenSettings: () -> Unit,
    onLock: () -> Unit,
) {
    val context = LocalContext.current
    val space = remember { SpaceManager(context) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val desktop by produceState<Desktop?>(null, refresh) {
        value = withContext(Dispatchers.IO) {
            val user = space.spaceUser()
            val apps = if (user != null && !space.isPaused(user)) {
                runCatching { space.spaceApps(user) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            Desktop(user, apps)
        }
    }

    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    val dock = listOf(
        Tool(Icons.Outlined.Lock, R.string.section_vault, onOpenVault),
        Tool(Icons.Outlined.Public, R.string.dock_browser, onOpenBrowser),
        Tool(Icons.Outlined.Shield, R.string.section_shield, onOpenShield),
        Tool(Icons.Outlined.Apps, R.string.dock_space, onOpenSpace),
        Tool(Icons.Outlined.Memory, R.string.dock_lab, onOpenLab),
    )

    fun launch(app: SpaceManager.App, user: UserHandle) {
        if (!space.launch(app, user)) {
            space.command(SpaceOwner.CMD_LAUNCH, listOf(app.packageName))?.let { runCatching { context.startActivity(it) } }
        }
    }

    Surface(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1440), SpirNight))),
        color = Color.Transparent,
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp)) {
            // Clock and quick actions.
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        DateFormat.getTimeFormat(context).format(now),
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Light,
                        color = Color.White,
                    )
                    Text(
                        DateFormat.format("EEEE, d MMMM", now).toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White)
                }
                IconButton(onClick = onLock) {
                    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.settings_lock_now), tint = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.home_search)) },
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White.copy(alpha = 0.12f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val d = desktop
                when {
                    d == null -> Unit
                    d.user == null -> NoSpaceCard(onOpenSpace)
                    d.apps.isEmpty() -> Text(
                        stringResource(R.string.home_space_empty),
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).clickable(onClick = onOpenSpace).padding(24.dp),
                    )
                    else -> {
                        val shown = d.apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(shown, key = { it.packageName }) { app ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { launch(app, d.user) }
                                        .alpha(if (app.frozen) 0.4f else 1f),
                                ) {
                                    Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(54.dp))
                                    Text(
                                        app.label,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Dock with SpirOS's own tools.
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(vertical = 10.dp),
            ) {
                dock.forEach { tool ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(onClick = tool.onClick).padding(4.dp),
                    ) {
                        Box(
                            Modifier.size(46.dp).clip(CircleShape).background(SpirAccent.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(tool.icon, contentDescription = null, tint = Color.White)
                        }
                        Text(
                            stringResource(tool.label),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoSpaceCard(onOpenSpace: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.home_no_space), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSpace) { Text(stringResource(R.string.space_create)) }
        }
    }
}
