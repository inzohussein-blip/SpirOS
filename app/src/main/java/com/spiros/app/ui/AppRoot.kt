package com.spiros.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.spiros.app.ui.browser.BrowserScreen
import com.spiros.app.ui.compat.CompatScreen
import com.spiros.app.ui.home.HomeScreen
import com.spiros.app.ui.lock.CalculatorScreen
import com.spiros.app.ui.lock.LockScreen
import com.spiros.app.ui.lock.SetupScreen
import com.spiros.app.ui.lab.LabScreen
import com.spiros.app.ui.settings.SettingsScreen
import com.spiros.app.ui.shield.ShieldScreen
import com.spiros.app.ui.space.SpaceScreen
import com.spiros.app.ui.vault.VaultScreen
import com.spiros.app.ui.vault.ViewerScreen
import com.spiros.app.vault.Vault
import com.spiros.app.vault.VaultItem

private sealed interface Screen {
    data object Home : Screen
    data object VaultList : Screen
    data class Viewer(val item: VaultItem) : Screen
    data object Space : Screen
    data object Lab : Screen
    data object Shield : Screen
    data object Browser : Screen
    data object Settings : Screen
    data object Compat : Screen
}

/** Nothing past the lock screen is composed until the vault is unlocked. */
@Composable
fun AppRoot(vault: Vault) {
    val status by vault.status.collectAsState()
    when (status) {
        Vault.Status.NEW -> SetupScreen(vault)
        Vault.Status.LOCKED -> if (Disguise.isEnabled(LocalContext.current)) CalculatorScreen(vault) else LockScreen(vault)
        Vault.Status.UNLOCKED -> Unlocked(vault)
    }
}

@Composable
private fun Unlocked(vault: Vault) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) {
        screen = when (screen) {
            is Screen.Viewer -> Screen.VaultList
            Screen.Compat -> Screen.Settings
            else -> Screen.Home
        }
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            onOpenVault = { screen = Screen.VaultList },
            onOpenSpace = { screen = Screen.Space },
            onOpenLab = { screen = Screen.Lab },
            onOpenShield = { screen = Screen.Shield },
            onOpenBrowser = { screen = Screen.Browser },
            onOpenSettings = { screen = Screen.Settings },
            onLock = vault::lock,
        )
        Screen.VaultList -> VaultScreen(vault, onBack = { screen = Screen.Home }, onOpen = { screen = Screen.Viewer(it) })
        is Screen.Viewer -> ViewerScreen(vault, s.item, onBack = { screen = Screen.VaultList })
        Screen.Space -> SpaceScreen(vault, onBack = { screen = Screen.Home })
        Screen.Lab -> LabScreen(vault, onBack = { screen = Screen.Home })
        Screen.Shield -> ShieldScreen(vault, onBack = { screen = Screen.Home })
        Screen.Browser -> BrowserScreen(vault, onBack = { screen = Screen.Home })
        Screen.Settings -> SettingsScreen(vault, onBack = { screen = Screen.Home }, onOpenCompat = { screen = Screen.Compat })
        Screen.Compat -> CompatScreen(onBack = { screen = Screen.Settings })
    }
}
