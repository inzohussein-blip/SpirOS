package com.spiros.app.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Installs the SpirOS Engine from SpirOS's own release (checked by [ApkInstaller]). */
object EngineInstaller {
    sealed interface State {
        data object Idle : State
        data class Downloading(val fraction: Float) : State
        data object Verifying : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun apkName() = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "SpirOS-Engine-x86_64.apk" else "SpirOS-Engine.apk"

    /** Android asks once to allow SpirOS to install apps. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Returns null on success or the error. */
    suspend fun install(context: Context): String? = withContext(Dispatchers.IO) {
        val error = ApkInstaller.install(context, Engine.PACKAGE, apkName()) { stage, f ->
            _state.value = when (stage) {
                ApkInstaller.Stage.DOWNLOADING -> State.Downloading(f)
                ApkInstaller.Stage.VERIFYING -> State.Verifying
                ApkInstaller.Stage.INSTALLING -> State.Installing
            }
        }
        _state.value = if (error == null) State.Idle else State.Failed(error)
        if (error == null) Engine.connect(context)
        error
    }
}

/** Result of an install session (engine or SpirOS); asks the user to confirm when Android requires it. */
class EngineInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(ApkInstaller.EXTRA_SESSION, -1)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> ApkInstaller.finished(session, null)
            else -> ApkInstaller.finished(
                session,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)",
            )
        }
    }
}
