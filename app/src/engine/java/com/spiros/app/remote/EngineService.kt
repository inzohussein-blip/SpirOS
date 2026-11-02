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
import com.spiros.app.linux.BackgroundTask
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
            .put("distro", LinuxDistro.installed(this@EngineService) ?: "")
            .apply {
                BackgroundTask.state?.let { t ->
                    put("task", JSONObject()
                        .put("kind", t.kind).put("name", t.name).put("stage", t.stage)
                        .put("done", t.done).put("total", t.total)
                        .put("paused", t.paused).put("active", t.active)
                        .put("error", t.error ?: ""))
                }
            }
            .toString()

        // ---- Linux system ----
        /** Default system (SpirOS Linux), reporting to [callback] until it ends. */
        override fun installLinux(callback: IEngineCallback) {
            installDistro("spiros", null, null, null)
            follow(callback)
        }

        override fun installDistro(desktop: String, url: String?, sha256: String?, file: ParcelFileDescriptor?) {
            val ctx = this@EngineService
            runTask("linux", desktop) {
                LinuxSession.stop()
                AiServer.stop()
                LinuxDistro.install(ctx, desktop, url, sha256, file)
            }
        }

        override fun pauseTask() = BackgroundTask.pause()

        override fun resumeTask() = BackgroundTask.resume()

        override fun cancelTask() = BackgroundTask.cancel()

        override fun clearTask() = BackgroundTask.clear()

        override fun runInDesktop(command: String) = LinuxSession.runInDesktop(this@EngineService, command)

        override fun removeLinux() {
            // Never delete the system under a running install.
            if (BackgroundTask.state?.let { it.active && it.kind == "linux" } == true) return
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
            val ctx = this@EngineService
            if (runTask("model", fileName) { AiModels.download(ctx, url, fileName) }) {
                follow(callback)
            } else {
                runCatching { callback.onDone(BUSY) }
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

    /** Runs [work] as the engine's background task; false if one is already running. */
    private fun runTask(kind: String, name: String, work: () -> Unit): Boolean {
        if (!BackgroundTask.begin(kind, name)) return false
        EngineKeepAlive.sync(this)
        thread(name = "engine-$kind") {
            val error = try {
                work()
                null
            } catch (e: BackgroundTask.Cancelled) {
                "cancelled"
            } catch (e: Throwable) {
                e.message ?: e.toString()
            }
            BackgroundTask.end(error)
            EngineKeepAlive.sync(this)
        }
        return true
    }

    /** Reports the current task's progress to [callback] until it ends. */
    private fun follow(callback: IEngineCallback) {
        thread(name = "engine-follow") {
            while (true) {
                val t = BackgroundTask.state ?: break
                if (!t.active) {
                    runCatching { callback.onDone(t.error) }
                    return@thread
                }
                runCatching { callback.onProgress(t.stage, t.fraction) }
                Thread.sleep(500)
            }
            runCatching { callback.onDone("no task") }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        /** onDone() error when another background job is already running. */
        const val BUSY = "busy"
    }
}
