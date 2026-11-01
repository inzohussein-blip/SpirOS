package com.spiros.app.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and installs the SpirOS Engine from SpirOS's own GitHub release.
 * The APK is checked twice before Android sees it: its SHA-256 must match the
 * published checksum, and it must be signed with exactly the same key as SpirOS
 * (so a tampered or foreign APK is never installed, whatever the download path).
 */
object EngineInstaller {
    private const val BASE = "https://github.com/inzohussein-blip/SpirOS/releases/download/dev-latest/"

    sealed interface State {
        data object Idle : State
        data class Downloading(val fraction: Float) : State
        data object Verifying : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun apkName() = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") "SpirOS-Engine-x86_64.apk" else "SpirOS-Engine.apk"

    /** Android asks once to allow SpirOS to install apps. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val apk = File(app.cacheDir, "engine.apk")
            _state.value = State.Downloading(0f)
            val expected = String(fetch(BASE + apkName() + ".sha256")).trim().substringBefore(' ').lowercase()
            download(BASE + apkName(), apk) { _state.value = State.Downloading(it) }
            _state.value = State.Verifying
            check(sha256(apk) == expected) { "Checksum mismatch" }
            check(sameSigner(app, apk)) { "The engine is not signed with the SpirOS key" }
            _state.value = State.Installing
            commit(app, apk)
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.toString())
        }
    }

    internal fun finished(error: String?) {
        _state.value = if (error == null) State.Idle else State.Failed(error)
    }

    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo ?: return false
        val ours = pm.getPackageInfo(context.packageName, flags).signingInfo ?: return false
        val a = (if (theirs.hasMultipleSigners()) theirs.apkContentsSigners else theirs.signingCertificateHistory).map { it.toCharsString() }
        val b = (if (ours.hasMultipleSigners()) ours.apkContentsSigners else ours.signingCertificateHistory).map { it.toCharsString() }
        return a.isNotEmpty() && a.toSet() == b.toSet()
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(Engine.PACKAGE)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("engine.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, EngineInstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "SpirOS")
    }

    private fun fetch(url: String): ByteArray {
        val c = open(url)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val c = open(url)
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val total = c.contentLengthLong
            target.outputStream().use { out ->
                c.inputStream.use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Result of the engine install session; asks the user to confirm when Android requires it. */
class EngineInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                EngineInstaller.finished(null)
                Engine.connect(context)
            }
            else -> EngineInstaller.finished(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)",
            )
        }
    }
}
