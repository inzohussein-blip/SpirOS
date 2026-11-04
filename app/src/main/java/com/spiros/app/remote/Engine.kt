package com.spiros.app.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import com.spiros.app.ai.AiModels
import com.spiros.app.ai.AiServer
import com.spiros.app.perf.Performance
import com.spiros.app.engine.VncClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * SpirOS's side of the SpirOS Engine: the headless helper app that runs Linux and the
 * private AI. SpirOS keeps all secrets (vault, space, keys); the engine only gets
 * commands. Before binding, the engine must be signed with exactly the same key as
 * SpirOS, and the engine only accepts SpirOS (signature permission).
 */
object Engine {
    const val PACKAGE = "com.spiros.app.engine"
    private const val SERVICE = "com.spiros.app.remote.EngineService"

    data class Status(
        val connected: Boolean = false,
        val version: Int = 0,
        val arch: String = "",
        val proot: Boolean = false,
        val linuxInstalled: Boolean = false,
        val desktopRunning: Boolean = false,
        val aiState: AiServer.State = AiServer.State.STOPPED,
        val aiModel: String = "",
        val frozen: Boolean = false,
        val memoryKb: Long = 0,
        /** Installed system: xfce, mate, cinnamon, custom, debian (older) or "". */
        val distro: String = "",
        val task: Task? = null,
        /** MetaTrader 5 full (Wine + MT5) is installed in the Linux system. */
        val mt5Installed: Boolean = false,
        /** Installed Windows programs and base: id -> package checksum ("" if not installed). */
        val addons: Map<String, String> = emptyMap(),
        /** Store installs waiting behind [task] (kind, name). */
        val queue: List<Pair<String, String>> = emptyList(),
        /** Jobs that ended recently (to announce them once). */
        val finished: List<Task> = emptyList(),
        /** Free space in the engine's storage. */
        val freeBytes: Long = 0,
    ) {
        val taskActive get() = task?.active == true
        val aiActive get() = aiState == AiServer.State.READY || aiState == AiServer.State.LOADING
        fun addonInstalled(id: String) = !addons[id].isNullOrEmpty() && !addons["base"].isNullOrEmpty()
    }

    /** The engine's background job (installing Linux or downloading a model). */
    data class Task(
        val kind: String,
        val name: String,
        val stage: String,
        val done: Long,
        val total: Long,
        val paused: Boolean,
        val active: Boolean,
        val error: String?,
        val step: Int = 0,
        val steps: Int = 0,
        val part: String = "",
        val waiting: String = "",
        val id: Long = 0,
    ) {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    data class User(val name: String, val uid: Int, val admin: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    val desktopRunning: StateFlow<Boolean> =
        status.map { it.desktopRunning }.stateIn(scope, SharingStarted.Eagerly, false)
    val aiState: StateFlow<AiServer.State> =
        status.map { it.aiState }.stateIn(scope, SharingStarted.Eagerly, AiServer.State.STOPPED)

    /** The desktop's screen in SpirOS while it runs. */
    private val _screen = MutableStateFlow<VncClient?>(null)
    val screen: StateFlow<VncClient?> = _screen.asStateFlow()

    @Volatile private var service: IEngine? = null
    @Volatile private var ready = CompletableDeferred<IEngine>()
    private var bound = false
    private var app: Context? = null
    private var poller: Job? = null

    /** Installed and signed with SpirOS's key (anything else is ignored). */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = IEngine.Stub.asInterface(binder)
            service = s
            ready.complete(s)
            app?.let { a -> runCatching { s.setWifiOnly(Performance.wifiOnly(a)) } }
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            ready = CompletableDeferred()
            _status.value = Status()
            closeScreen()
        }

        override fun onBindingDied(name: ComponentName?) {
            // The engine was updated or reinstalled: bind again.
            onServiceDisconnected(name)
            synchronized(this@Engine) {
                runCatching { app?.unbindService(this) }
                bound = false
            }
        }
    }

    /** Binds to the engine if it is installed and genuine. Cheap to call repeatedly. */
    @Synchronized
    fun connect(context: Context): Boolean {
        val app = context.applicationContext
        this.app = app
        if (bound) return true
        if (!isInstalled(app)) return false
        bound = runCatching {
            app.bindService(
                Intent().setClassName(PACKAGE, SERVICE),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (bound && poller == null) {
            poller = scope.launch {
                while (isActive) {
                    refresh()
                    delay(
                        when {
                            _status.value.taskActive -> 1000
                            _status.value.desktopRunning || _status.value.aiActive -> 1500
                            else -> 4000
                        },
                    )
                }
            }
        }
        return bound
    }

    fun refresh() {
        val s = service ?: return
        val json = runCatching { JSONObject(s.status()) }.getOrNull() ?: return
        val next = Status(
            connected = true,
            version = json.optInt("version"),
            arch = json.optString("arch"),
            proot = json.optBoolean("proot"),
            linuxInstalled = json.optBoolean("linuxInstalled"),
            desktopRunning = json.optBoolean("desktopRunning"),
            aiState = runCatching { AiServer.State.valueOf(json.optString("aiState")) }.getOrDefault(AiServer.State.STOPPED),
            aiModel = json.optString("aiModel"),
            frozen = json.optBoolean("frozen"),
            memoryKb = json.optLong("memoryKb"),
            distro = json.optString("distro"),
            mt5Installed = json.optBoolean("mt5Installed"),
            addons = json.optJSONObject("addons")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty(),
            task = json.optJSONObject("task")?.let(::task),
            queue = json.optJSONArray("queue")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("kind") to it.optString("name") } }.orEmpty(),
            finished = json.optJSONArray("finished")?.let { a -> (0 until a.length()).map { task(a.getJSONObject(it)) } }.orEmpty(),
            freeBytes = json.optLong("freeBytes"),
        )
        _status.value = next
        if (!next.desktopRunning) closeScreen()
        LinuxApps.onStatus(next)
        app?.let { TaskNotifier.onStatus(it, next) }
    }

    private fun task(it: JSONObject) = Task(
        kind = it.optString("kind"),
        name = it.optString("name"),
        stage = it.optString("stage"),
        done = it.optLong("done"),
        total = it.optLong("total"),
        paused = it.optBoolean("paused"),
        active = it.optBoolean("active"),
        error = it.optString("error").ifEmpty { null },
        step = it.optInt("step"),
        steps = it.optInt("steps"),
        part = it.optString("part"),
        waiting = it.optString("waiting"),
        id = it.optLong("id"),
    )

    /** Runs [block] against the engine (waits briefly for the connection), off the main thread. */
    suspend fun <T> call(context: Context, block: (IEngine) -> T): T? = withContext(Dispatchers.IO) {
        if (!connect(context)) return@withContext null
        val s = service ?: withTimeoutOrNull(8000) { ready.await() } ?: return@withContext null
        runCatching { block(s) }.getOrNull().also { refresh() }
    }

    /** Fire-and-forget command, off the calling thread; safe from anywhere (Panic, notifications). */
    fun send(context: Context, block: (IEngine) -> Unit) {
        val app = context.applicationContext
        scope.launch { if (service != null || isInstalled(app)) call(app, block) }
    }

    // ---- desktop -------------------------------------------------------------------

    fun startDesktop(context: Context, geometry: String, dpi: Int, light: Boolean, singleApp: String?, fps: Int) {
        scope.launch {
            call(context) { it.startDesktop(geometry, dpi, light, singleApp.orEmpty()) } ?: return@launch
            openScreen(fps)
        }
    }

    /** Starts the desktop with the user's Performance & battery settings. */
    fun startDesktop(context: Context, geometry: String) {
        val single = Performance.linuxSession(context) == Performance.Session.SINGLE_APP
        startDesktop(
            context, Performance.resolve(context, geometry), dpi = 120,
            light = Performance.linuxLight(context),
            singleApp = if (single) Performance.singleApp(context) else null,
            fps = Performance.linuxFps(context),
        )
    }

    /**
     * Installs a Linux system in the background (see IEngine.installDistro). For a
     * custom system from a file, [file] is a document the user picked; SpirOS opens it
     * and hands the engine only that one file.
     */
    fun installDistro(context: Context, desktop: String, url: String? = null, sha256: String? = null, file: Uri? = null) {
        val app = context.applicationContext
        scope.launch {
            val pfd = file?.let { runCatching { app.contentResolver.openFileDescriptor(it, "r") }.getOrNull() }
            call(app) { it.installDistro(desktop, url, sha256, pfd) }
            runCatching { pfd?.close() }
        }
    }

    fun installAddon(context: Context, id: String) = send(context) { it.installAddon(id) }
    fun removeAddon(context: Context, id: String) = send(context) { it.removeAddon(id) }
    fun dequeue(context: Context, kind: String, name: String) = send(context) { it.dequeue(kind, name) }
    fun setWifiOnly(context: Context, on: Boolean) = send(context) { it.setWifiOnly(on) }

    fun pauseTask(context: Context) = send(context) { it.pauseTask() }
    fun resumeTask(context: Context) = send(context) { it.resumeTask() }
    fun cancelTask(context: Context) = send(context) { it.cancelTask() }
    fun clearTask(context: Context) = send(context) { it.clearTask() }

    fun startAi(context: Context, model: AiModels.Model) = send(context) {
        it.startAi(model.fileName, Performance.aiThreads(context), Performance.aiContext(context))
    }

    /** Re-attach to a desktop that is already running (e.g. after SpirOS restarted). */
    fun attachScreen(fps: Int) {
        if (_screen.value == null && service != null) openScreen(fps)
    }

    private fun openScreen(fps: Int) {
        closeScreen()
        _screen.value = VncClient(
            connector = { service?.openDisplay()?.let(VncClient::fromDescriptor) },
            connectTimeoutMs = 120_000,
            maxFps = fps,
        ).also { it.start() }
    }

    private fun closeScreen() {
        _screen.value?.close()
        _screen.value = null
    }

    fun stopDesktop(context: Context) {
        closeScreen()
        send(context) { it.stopDesktop() }
    }

    fun stopAll(context: Context) {
        closeScreen()
        send(context) { it.stopAll() }
    }

    // ---- helpers for screens -------------------------------------------------------

    fun parseUsers(json: String?): List<User> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { User(it.getString("name"), it.getInt("uid"), it.getBoolean("admin")) }
    }.getOrDefault(emptyList())

    fun parseStrings(json: String?): List<String> = runCatching {
        val a = JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** Adapts a progress callback for engine tasks; [onDone] receives the error or null. */
    fun callback(onProgress: (String, Float) -> Unit, onDone: (String?) -> Unit) = object : IEngineCallback.Stub() {
        override fun onProgress(stage: String, fraction: Float) = onProgress(stage, fraction)
        override fun onDone(error: String?) = onDone(error)
    }
}
