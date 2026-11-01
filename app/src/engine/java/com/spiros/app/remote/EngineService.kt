package com.spiros.app.remote

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.spiros.app.BuildConfig
import com.spiros.app.ai.AiModels
import com.spiros.app.ai.AiServer
import com.spiros.app.linux.LinuxAccounts
import com.spiros.app.linux.LinuxDistro
import com.spiros.app.linux.LinuxSession
import com.spiros.app.perf.AppProcesses
import com.spiros.app.perf.BackgroundFreezer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The SpirOS Engine: runs Linux (proot), the desktop and the private AI on behalf of
 * SpirOS. It holds no secrets: no vault, no keys, no space. Only apps signed with
 * the SpirOS key can bind (signature permission in the manifest).
 */
class EngineService : Service() {

    private val binder = object : IEngine.Stub() {
        override fun version() = BuildConfig.VERSION_CODE

        override fun status(): String = JSONObject()
            .put("version", BuildConfig.VERSION_CODE)
            .put("arch", LinuxDistro.arch() ?: "")
            .put("proot", LinuxSession.prootAvailable(this@EngineService))
            .put("linuxInstalled", LinuxDistro.isInstalled(this@EngineService))
            .put("desktopRunning", LinuxSession.running.value)
            .put("aiState", AiServer.state.value.name)
            .put("aiModel", AiServer.modelFile ?: "")
            .put("frozen", BackgroundFreezer.frozen)
            .put("memoryKb", AppProcesses.list().sumOf { it.rssKb })
            .toString()

        // ---- Linux system ----
        override fun installLinux(callback: IEngineCallback) {
            thread(name = "engine-install") {
                val result = runCatching {
                    runBlocking {
                        LinuxDistro.install(this@EngineService) { stage, f -> runCatching { callback.onProgress(stage.name, f) } }
                    }
                }
                runCatching { callback.onDone(result.exceptionOrNull()?.let { it.message ?: it.toString() }) }
            }
        }

        override fun removeLinux() {
            LinuxSession.stop()
            AiServer.stop()
            LinuxDistro.remove(this@EngineService)
        }

        override fun startDesktop(geometry: String, dpi: Int, light: Boolean, singleApp: String?) {
            LinuxSession.start(this@EngineService, LinuxSession.Options(geometry, dpi, light, singleApp?.ifBlank { null }))
            EngineKeepAlive.sync(this@EngineService)
        }

        override fun stopDesktop() {
            LinuxSession.stop()
            EngineKeepAlive.sync(this@EngineService)
        }

        override fun openDisplay(): ParcelFileDescriptor? {
            val path = LinuxSession.displaySocket(this@EngineService)
            if (!path.exists()) return null
            return runCatching {
                val socket = LocalSocket()
                socket.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
                // Hand SpirOS its own copy of the connected socket; ours is closed here.
                ParcelFileDescriptor.dup(socket.fileDescriptor).also { socket.close() }
            }.getOrNull()
        }

        override fun desktopLog(): List<String> = LinuxSession.log.value.takeLast(80)

        // ---- Linux accounts ----
        override fun users(): String = JSONArray().apply {
            LinuxAccounts.users(this@EngineService).forEach {
                put(JSONObject().put("name", it.name).put("uid", it.uid).put("admin", it.admin))
            }
        }.toString()

        override fun desktopUser(): String = LinuxAccounts.desktopUser(this@EngineService).name

        override fun setDesktopUser(name: String) = LinuxAccounts.setDesktopUser(this@EngineService, name)

        override fun account(action: String, name: String, password: String?, admin: Boolean): String? {
            val ctx = this@EngineService
            val result = runCatching {
                runBlocking {
                    when (action) {
                        "add" -> LinuxAccounts.addUser(ctx, name, password.orEmpty(), admin)
                        "password" -> LinuxAccounts.setPassword(ctx, name, password.orEmpty())
                        "admin" -> LinuxAccounts.setAdmin(ctx, name, admin)
                        "delete" -> LinuxAccounts.deleteUser(ctx, name)
                        else -> Result.failure(IllegalArgumentException(action))
                    }
                }
            }.getOrElse { Result.failure(it) }
            return result.exceptionOrNull()?.let { it.message ?: it.toString() }
        }

        // ---- Private AI ----
        override fun downloadedModels(): String = JSONArray(AiModels.downloaded(this@EngineService)).toString()

        override fun downloadModel(url: String, fileName: String, callback: IEngineCallback) {
            thread(name = "engine-model") {
                val result = runCatching {
                    runBlocking { AiModels.download(this@EngineService, url, fileName) { runCatching { callback.onProgress("download", it) } } }
                }
                runCatching { callback.onDone(result.exceptionOrNull()?.let { it.message ?: it.toString() }) }
            }
        }

        override fun deleteModel(fileName: String) {
            AiModels.delete(this@EngineService, fileName)
        }

        override fun startAi(fileName: String, threads: Int, contextSize: Int) {
            AiServer.start(this@EngineService, fileName, threads, contextSize)
            EngineKeepAlive.sync(this@EngineService)
        }

        override fun stopAi() {
            AiServer.stop()
            EngineKeepAlive.sync(this@EngineService)
        }

        override fun aiKey(): String = AiServer.apiKey

        override fun aiPort(): Int = AiServer.PORT

        override fun aiLog(): List<String> = AiServer.log.value.takeLast(40)

        // ---- Power ----
        override fun freeze() = BackgroundFreezer.freeze()

        override fun resume() = BackgroundFreezer.resume()

        override fun stopAll() {
            BackgroundFreezer.resume()
            LinuxSession.stop()
            AiServer.stop()
            EngineKeepAlive.sync(this@EngineService)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
