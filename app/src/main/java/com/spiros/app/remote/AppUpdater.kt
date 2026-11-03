package com.spiros.app.remote

import android.content.Context
import android.os.Build
import com.spiros.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Updates SpirOS (and the engine, if installed) from SpirOS's latest GitHub release,
 * from Settings. Every APK is checked (SHA-256 + same signing key) before installing.
 */
object AppUpdater {
    data class Release(val versionCode: Int, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val release: Release) : State
        data class Available(val release: Release) : State
        /** [engine]: the engine is being updated (before SpirOS itself). */
        data class Working(val engine: Boolean, val stage: ApkInstaller.Stage, val fraction: Float) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val current get() = BuildConfig.VERSION_CODE

    private fun apkName() = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "SpirOS-x86_64.apk" else "SpirOS.apk"

    /** Installed engine's version, or null if not installed. */
    fun engineVersion(context: Context): Long? = runCatching {
        context.packageManager.getPackageInfo(Engine.PACKAGE, 0).longVersionCode
    }.getOrNull()

    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        if (_state.value is State.Working) return@withContext null
        _state.value = State.Checking
        try {
            val json = JSONObject(ApkInstaller.text(ApkInstaller.BASE + "version.json"))
            val release = Release(json.getInt("versionCode"), json.optString("notes"))
            val engineOld = engineVersion(context)?.let { it < release.versionCode } == true
            _state.value = if (release.versionCode > current || engineOld) State.Available(release) else State.UpToDate(release)
            release
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.toString())
            null
        }
    }

    /** Engine first (SpirOS keeps running meanwhile), then SpirOS itself (Android restarts it). */
    suspend fun update(context: Context) = withContext(Dispatchers.IO) {
        val release = (_state.value as? State.Available)?.release ?: check(context) ?: return@withContext
        if (engineVersion(context)?.let { it < release.versionCode } == true) {
            val error = ApkInstaller.install(context, Engine.PACKAGE, EngineInstaller.apkName()) { s, f ->
                _state.value = State.Working(engine = true, stage = s, fraction = f)
            }
            if (error != null) { _state.value = State.Failed(error); return@withContext }
            Engine.connect(context)
        }
        if (release.versionCode > current) {
            val error = ApkInstaller.install(context, context.packageName, apkName()) { s, f ->
                _state.value = State.Working(engine = false, stage = s, fraction = f)
            }
            _state.value = if (error != null) State.Failed(error) else State.UpToDate(release)
        } else {
            _state.value = State.UpToDate(release)
        }
    }
}
