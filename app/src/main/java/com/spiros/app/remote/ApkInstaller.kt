package com.spiros.app.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads an APK from SpirOS's latest GitHub release and installs it. Before Android
 * sees it, the APK must match its published SHA-256 and be signed with exactly the
 * same key as SpirOS, so a tampered or foreign APK is never installed.
 */
object ApkInstaller {
    const val BASE = "https://github.com/inzohussein-blip/SpirOS/releases/latest/download/"

    enum class Stage { DOWNLOADING, VERIFYING, INSTALLING }

    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<String?>>()

    /**
     * Installs [apkName] as [packageName]; returns null on success or the error.
     * Blocking (call off the main thread). When SpirOS updates itself, Android ends
     * the process on success, so this only returns for errors.
     */
    suspend fun install(context: Context, packageName: String, apkName: String, onStage: (Stage, Float) -> Unit): String? = try {
        val app = context.applicationContext
        val apk = File(app.cacheDir, "update-$apkName")
        onStage(Stage.DOWNLOADING, 0f)
        val expected = String(fetch(BASE + apkName + ".sha256")).trim().substringBefore(' ').lowercase()
        download(BASE + apkName, apk) { onStage(Stage.DOWNLOADING, it) }
        onStage(Stage.VERIFYING, 1f)
        check(sha256(apk) == expected) { "Checksum mismatch" }
        check(sameSigner(app, apk)) { "Not signed with the SpirOS key" }
        onStage(Stage.INSTALLING, 1f)
        val done = CompletableDeferred<String?>()
        val id = commit(app, apk, packageName)
        waiting[id] = done
        done.await().also { waiting.remove(id); apk.delete() }
    } catch (e: Exception) {
        e.message ?: e.toString()
    }

    /** Called by [EngineInstallReceiver] with the session's result. */
    internal fun finished(sessionId: Int, error: String?) {
        waiting[sessionId]?.complete(error)
    }

    fun text(url: String) = String(fetch(url))

    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo ?: return false
        val ours = pm.getPackageInfo(context.packageName, flags).signingInfo ?: return false
        if (theirs.hasMultipleSigners() || ours.hasMultipleSigners()) {
            return theirs.apkContentsSigners.map { it.toCharsString() }.toSet() == ours.apkContentsSigners.map { it.toCharsString() }.toSet()
        }
        // Key rotation (v3): the update's signing history must contain the key this
        // SpirOS is signed with now (old key -> private release key is accepted; an
        // APK signed only with an older key is not).
        val theirHistory = theirs.signingCertificateHistory.map { it.toCharsString() }
        val ourCurrent = ours.signingCertificateHistory.lastOrNull()?.toCharsString() ?: return false
        return ourCurrent in theirHistory
    }

    private fun commit(context: Context, apk: File, packageName: String): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, EngineInstallReceiver::class.java).putExtra(EXTRA_SESSION, id)
            val pending = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        return id
    }

    internal const val EXTRA_SESSION = "com.spiros.app.extra.SESSION"

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
