package com.spiros.app.ui.space

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spiros.app.R
import com.spiros.app.space.SpaceManager

/**
 * A profile SpirOS does not own. Android allows only its own admin app, or the user
 * in system Settings, to delete it; SpirOS shows what it is, opens its apps, and takes
 * the user straight to where it can be removed.
 */
@Composable
fun OtherProfileCard(
    profile: SpaceManager.OtherProfile,
    space: SpaceManager,
    /** Called before opening system Settings, so SpirOS stays unlocked for the round trip. */
    onLeaveApp: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val typeLabel = stringResource(
        when (profile.type) {
            SpaceManager.ProfileType.WORK -> R.string.other_type_work
            SpaceManager.ProfileType.CLONE -> R.string.other_type_clone
            SpaceManager.ProfileType.PRIVATE -> R.string.other_type_private
            SpaceManager.ProfileType.UNKNOWN -> R.string.other_type_unknown
        },
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(typeLabel, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    profile.isOldSpirOS -> stringResource(R.string.other_owner_old_spiros)
                    profile.owner != null -> stringResource(R.string.other_owner, profile.owner)
                    else -> stringResource(R.string.other_owner_unknown)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profile.paused) {
                Text(stringResource(R.string.space_paused), color = MaterialTheme.colorScheme.secondary)
            }

            // Its apps: SpirOS may open them even though it does not own the profile.
            if (profile.apps.isNotEmpty()) {
                Text(stringResource(R.string.other_apps, profile.apps.size), style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    profile.apps.forEach { app ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(64.dp)
                                .clickable { space.launch(app, profile.user) },
                        ) {
                            Image(app.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(44.dp))
                            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // How to remove it.
            Text(
                stringResource(
                    when (profile.type) {
                        SpaceManager.ProfileType.CLONE -> R.string.other_remove_steps_clone
                        SpaceManager.ProfileType.PRIVATE -> R.string.other_remove_steps_private
                        else -> R.string.other_remove_steps_work
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    onLeaveApp()
                    openRemovalSettings(context, profile.type)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.other_open_settings)) }

            val ownerPkg = profile.ownerPackage
            if (ownerPkg != null && !profile.isOldSpirOS) {
                OutlinedButton(
                    onClick = { openOwnerApp(context, space, profile, ownerPkg) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.other_open_owner, profile.owner ?: ownerPkg)) }
            }
            OutlinedButton(
                onClick = {
                    if (!space.setPaused(profile.user, !profile.paused)) {
                        onLeaveApp()
                        openRemovalSettings(context, profile.type)
                    }
                    onChanged()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (profile.paused) R.string.space_resume else R.string.other_pause)) }
        }
    }
}

/** Accounts settings hold "Remove work profile" on stock Android and most skins. */
internal fun openRemovalSettings(context: Context, type: SpaceManager.ProfileType) {
    val candidates = when (type) {
        SpaceManager.ProfileType.CLONE -> listOf(Settings.ACTION_APPLICATION_SETTINGS, Settings.ACTION_SETTINGS)
        SpaceManager.ProfileType.PRIVATE -> listOf(Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS)
        else -> listOf(Settings.ACTION_SYNC_SETTINGS, Settings.ACTION_SETTINGS)
    }
    for (action in candidates) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next screen
        }
    }
}

/** Removing a profile from inside its admin app is usually the cleanest way. */
private fun openOwnerApp(context: Context, space: SpaceManager, profile: SpaceManager.OtherProfile, pkg: String) {
    val inProfile = profile.apps.firstOrNull { it.packageName == pkg }
    if (inProfile != null && space.launch(inProfile, profile.user)) return
    val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
    runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
