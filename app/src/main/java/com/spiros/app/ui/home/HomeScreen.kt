package com.spiros.app.ui.home

import android.os.UserHandle
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spiros.app.R
import com.spiros.app.ai.AiServer
import com.spiros.app.security.Panic
import com.spiros.app.remote.Engine
import com.spiros.app.shield.ShieldSettings
import com.spiros.app.shield.ShieldVpnService
import com.spiros.app.space.SpaceManager
import com.spiros.app.space.SpaceOwner
import com.spiros.app.ui.theme.LocalElegant
import com.spiros.app.ui.theme.ToolColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

private data class Tool(val icon: ImageVector, @StringRes val label: Int, val color: Color, val onClick: () -> Unit)

private data class Desktop(val user: UserHandle?, val apps: List<SpaceManager.App>)

/**
 * The SpirOS "second interface": status at a glance, SpirOS's tools as colourful
 * tiles, and the isolated space's apps, in one scrolling launcher.
 */
@Composable
fun HomeScreen(
    onOpenVault: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenAi: () -> Unit,
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
    val shieldPhone by ShieldVpnService.running.collectAsState()
    val linuxRunning by Engine.desktopRunning.collectAsState()
    val aiState by Engine.aiState.collectAsState()
    val shieldSpace = remember(refresh) { ShieldSettings.spaceShield(context) }

    val tools = listOf(
        Tool(Icons.Rounded.Lock, R.string.section_vault, ToolColors.Vault, onOpenVault),
        Tool(Icons.Rounded.Public, R.string.dock_browser, ToolColors.Browser, onOpenBrowser),
        Tool(Icons.Rounded.Shield, R.string.section_shield, ToolColors.Shield, onOpenShield),
        Tool(Icons.Rounded.Apps, R.string.dock_space, ToolColors.Space, onOpenSpace),
        Tool(Icons.Rounded.Computer, R.string.dock_linux, ToolColors.Linux, onOpenLinux),
        Tool(Icons.Rounded.AutoAwesome, R.string.dock_ai, ToolColors.Ai, onOpenAi),
        Tool(Icons.Rounded.Memory, R.string.dock_lab, ToolColors.Lab, onOpenLab),
        Tool(Icons.Rounded.Settings, R.string.settings, ToolColors.Settings, onOpenSettings),
    )

    fun launch(app: SpaceManager.App, user: UserHandle) {
        if (!space.launch(app, user)) {
            space.command(SpaceOwner.CMD_LAUNCH, listOf(app.packageName))?.let { runCatching { context.startActivity(it) } }
        }
    }

    val elegant = LocalElegant.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        Modifier
            .fillMaxSize()
            .background(
                if (elegant) {
                    Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.background, scheme.background))
                } else {
                    Brush.verticalGradient(listOf(scheme.background, scheme.background))
                },
            ),
        color = Color.Transparent,
        contentColor = scheme.onBackground,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            fullWidth {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            DateFormat.getTimeFormat(context).format(now),
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            DateFormat.format("EEEE, d MMMM", now).toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        )
                    }
                    PanicButton()
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(Icons.Rounded.Lock, stringResource(R.string.settings_lock_now), onLock)
                }
            }

            // Status at a glance; each chip opens its screen.
            fullWidth {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusChip(
                        stringResource(R.string.section_shield),
                        stringResource(if (shieldPhone || shieldSpace) R.string.status_on else R.string.status_off),
                        ToolColors.Shield,
                        active = shieldPhone || shieldSpace,
                        onClick = onOpenShield,
                    )
                    StatusChip(
                        stringResource(R.string.dock_space),
                        stringResource(if (desktop?.user != null) R.string.status_ready else R.string.status_not_set),
                        ToolColors.Space,
                        active = desktop?.user != null,
                        onClick = onOpenSpace,
                    )
                    StatusChip(
                        stringResource(R.string.dock_linux),
                        stringResource(if (linuxRunning) R.string.status_running else R.string.status_off),
                        ToolColors.Linux,
                        active = linuxRunning,
                        onClick = onOpenLinux,
                    )
                    StatusChip(
                        stringResource(R.string.dock_ai),
                        stringResource(
                            when (aiState) {
                                AiServer.State.READY -> R.string.status_running
                                AiServer.State.LOADING -> R.string.status_starting
                                else -> R.string.status_off
                            },
                        ),
                        ToolColors.Ai,
                        active = aiState == AiServer.State.READY,
                        onClick = onOpenAi,
                    )
                }
            }

            fullWidth { Dashboard() }

            fullWidth { SectionTitle(stringResource(R.string.home_tools)) }
            items(tools, key = { it.label }) { ToolTile(it) }

            fullWidth { SectionTitle(stringResource(R.string.home_space_apps)) }
            val d = desktop
            when {
                d == null -> Unit
                d.user == null -> fullWidth { NoSpaceCard(onOpenSpace) }
                d.apps.isEmpty() -> fullWidth {
                    Text(
                        stringResource(R.string.home_space_empty),
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSpace).padding(24.dp),
                    )
                }
                else -> {
                    fullWidth {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            placeholder = { Text(stringResource(R.string.home_search)) },
                            shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                                focusedContainerColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f),
                                unfocusedBorderColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val shown = d.apps.filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
                    items(shown, key = { it.packageName }) { app -> AppIcon(app) { launch(app, d.user) } }
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** Red Panic button: stop everything, forget the key, leave SpirOS instantly. */
@Composable
private fun PanicButton() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.error)
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                Panic.trigger(context)
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.panic), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(top = 8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun StatusChip(label: String, value: String, color: Color, active: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) color else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)),
        )
        Spacer(Modifier.width(8.dp))
        Text("$label · $value", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToolTile(tool: Tool) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = tool.onClick)
            .padding(vertical = 4.dp),
    ) {
        val elegant = LocalElegant.current
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .aspectRatio(1f)
                .then(if (elegant) Modifier.shadow(8.dp, RoundedCornerShape(20.dp), spotColor = tool.color) else Modifier)
                .clip(RoundedCornerShape(20.dp))
                .background(if (elegant) ToolColors.gradient(tool.color) else Brush.linearGradient(listOf(tool.color.copy(alpha = 0.16f), tool.color.copy(alpha = 0.16f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                tool.icon,
                contentDescription = null,
                tint = if (elegant) Color.White else tool.color,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            stringResource(tool.label),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AppIcon(app: SpaceManager.App, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .alpha(if (app.frozen) 0.4f else 1f)
            .padding(vertical = 4.dp),
    ) {
        val image = remember(app.icon) { app.icon.asImageBitmap() }
        Image(image, contentDescription = null, modifier = Modifier.size(54.dp))
        Text(
            app.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun NoSpaceCard(onOpenSpace: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(ToolColors.Space.copy(alpha = 0.12f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Apps, contentDescription = null, tint = ToolColors.Space, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_no_space), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenSpace) { Text(stringResource(R.string.space_create)) }
    }
}
