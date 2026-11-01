package com.spiros.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.LaunchedEffect
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
import com.spiros.app.ui.linux.LinuxAccountsScreen
import com.spiros.app.ui.performance.PerformanceScreen
import com.spiros.app.ui.linux.LinuxScreen
import com.spiros.app.ui.ai.AiScreen
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
    data object Linux : Screen
    data object Ai : Screen
    data object LinuxAccounts : Screen
    data object Performance : Screen
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
        Vault.Status.LOCKED -> when {
            // PIN lock turned off in Settings: open straight away.
            !vault.isLockEnabled -> {
                // PIN lock turned off in Settings: open straight away (PIN screen if that fails).
                var failed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { failed = !withContext(Dispatchers.IO) { vault.openWithoutPin() } }
                if (failed) LockScreen(vault)
            }
            Disguise.isEnabled(LocalContext.current) -> CalculatorScreen(vault)
            else -> LockScreen(vault)
        }
        Vault.Status.UNLOCKED -> Unlocked(vault)
    }
}

@Composable
private fun Unlocked(vault: Vault) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) {
        screen = when (screen) {
            is Screen.Viewer -> Screen.VaultList
            Screen.Compat, Screen.LinuxAccounts, Screen.Performance -> Screen.Settings
            else -> Screen.Home
        }
    }

    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // Deeper screens slide in from the reading end; going back reverses it.
            val forward = depth(targetState) > depth(initialState)
            val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
            (slideInHorizontally(tween(260)) { it / 4 * sign } + fadeIn(tween(220)))
                .togetherWith(slideOutHorizontally(tween(260)) { -it / 4 * sign } + fadeOut(tween(180)))
        },
        label = "screens",
    ) { current -> ScreenContent(current, vault) { screen = it } }
}

private fun depth(screen: Screen) = when (screen) {
    Screen.Home -> 0
    is Screen.Viewer, Screen.Compat, Screen.LinuxAccounts, Screen.Performance -> 2
    else -> 1
}

@Composable
private fun ScreenContent(current: Screen, vault: Vault, navigate: (Screen) -> Unit) {
    when (val s = current) {
        Screen.Home -> HomeScreen(
            onOpenVault = { navigate(Screen.VaultList) },
            onOpenSpace = { navigate(Screen.Space) },
            onOpenLab = { navigate(Screen.Lab) },
            onOpenLinux = { navigate(Screen.Linux) },
            onOpenAi = { navigate(Screen.Ai) },
            onOpenShield = { navigate(Screen.Shield) },
            onOpenBrowser = { navigate(Screen.Browser) },
            onOpenSettings = { navigate(Screen.Settings) },
            onLock = vault::lock,
        )
        Screen.VaultList -> VaultScreen(vault, onBack = { navigate(Screen.Home) }, onOpen = { navigate(Screen.Viewer(it)) })
        is Screen.Viewer -> ViewerScreen(vault, s.item, onBack = { navigate(Screen.VaultList) })
        Screen.Space -> SpaceScreen(vault, onBack = { navigate(Screen.Home) })
        Screen.Linux -> LinuxScreen(onBack = { navigate(Screen.Home) })
        Screen.Ai -> AiScreen(onBack = { navigate(Screen.Home) }, onOpenLinux = { navigate(Screen.Linux) })
        Screen.Lab -> LabScreen(vault, onBack = { navigate(Screen.Home) })
        Screen.Shield -> ShieldScreen(vault, onBack = { navigate(Screen.Home) })
        Screen.Browser -> BrowserScreen(vault, onBack = { navigate(Screen.Home) })
        Screen.Settings -> SettingsScreen(
            vault,
            onBack = { navigate(Screen.Home) },
            onOpenCompat = { navigate(Screen.Compat) },
            onOpenLinuxAccounts = { navigate(Screen.LinuxAccounts) },
            onOpenPerformance = { navigate(Screen.Performance) },
        )
        Screen.LinuxAccounts -> LinuxAccountsScreen(onBack = { navigate(Screen.Settings) })
        Screen.Performance -> PerformanceScreen(vault, onBack = { navigate(Screen.Settings) })
        Screen.Compat -> CompatScreen(onBack = { navigate(Screen.Settings) })
    }
}
