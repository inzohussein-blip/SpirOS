package com.spiros.app.linux

import android.content.Context
import com.spiros.app.engine.VncClient
import com.spiros.app.perf.Performance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the Debian desktop with proot (no emulation: Linux programs run directly on
 * the phone's CPU, inside SpirOS's app sandbox). Speed edition only: Android allows
 * running programs from app storage only for apps targeting API 28 or lower.
 */
object LinuxSession {
    private const val MAX_LINES = 500

    private var process: Process? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _screen = MutableStateFlow<VncClient?>(null)
    val screen: StateFlow<VncClient?> = _screen.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun prootAvailable(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libproot.so").isFile

    @Synchronized
    fun start(context: Context, geometry: String, dpi: Int) {
        stop()
        _log.value = emptyList()
        val root = LinuxDistro.root(context)
        installLauncher(context, root)
        val socket = File(root, "tmp/spiros-vnc").apply { delete() }
        val singleApp = Performance.linuxSession(context) == Performance.Session.SINGLE_APP
        val p = command(context, root, user = LinuxAccounts.desktopUser(context), envAndCommand = listOf(
            "SPIROS_GEOMETRY=$geometry",
            "SPIROS_DPI=$dpi",
            "SPIROS_LIGHT=${if (Performance.linuxLight(context)) 1 else 0}",
            "SPIROS_SESSION=${if (singleApp) "app" else "desktop"}",
            "SPIROS_APP=${Performance.singleApp(context)}",
            "/usr/local/bin/spiros-desktop",
        )).start()
        process = p
        _running.value = true
        thread(name = "linux-log", isDaemon = true) { pump(p) }
        _screen.value = VncClient(socket.path, connectTimeoutMs = 90_000, maxFps = Performance.linuxFps(context))
            .also { it.start() }
    }

    @Synchronized
    fun stop() {
        _screen.value?.close()
        _screen.value = null
        process?.let { p ->
            p.destroy()
            thread(isDaemon = true) {
                Thread.sleep(3000)
                if (p.isAlive) p.destroyForcibly()
            }
        }
        process = null
        _running.value = false
    }

    /** Always run the launcher shipped with this app version (assets/linux/spiros-desktop). */
    private fun installLauncher(context: Context, root: File) {
        val target = File(root, "usr/local/bin/spiros-desktop")
        target.parentFile?.mkdirs()
        context.assets.open("linux/spiros-desktop").use { input -> target.outputStream().use { input.copyTo(it) } }
        target.setExecutable(true, false)
    }

    /**
     * proot invocation that runs [envAndCommand] inside the Debian root, as fake root
     * by default or as [user] (proot --change-id), with that user's HOME and USER.
     */
    fun command(
        context: Context,
        root: File,
        envAndCommand: List<String>,
        user: LinuxAccounts.User = LinuxAccounts.ROOT,
    ): ProcessBuilder {
        val lib = context.applicationInfo.nativeLibraryDir
        val tmp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        File(root, "tmp").mkdirs()
        val fakes = FakeProc.prepare(context)
        val args = buildList {
            add("$lib/libproot.so")
            if (user.uid == 0) add("-0") else { add("-i"); add("${user.uid}:${user.gid}") }
            add("--link2symlink")
            add("--kill-on-exit")
            add("-r"); add(root.path)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("${root.path}/tmp:/dev/shm")
            // Android hides parts of /proc from apps; give programs harmless stand-ins.
            fakes.forEach { (file, target) -> add("-b"); add("${file.path}:$target") }
            add("-w"); add(user.home)
            add("/usr/bin/env"); add("-i")
            add("HOME=${user.home}")
            add("USER=${user.name}")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            addAll(envAndCommand)
        }
        return ProcessBuilder(args).redirectErrorStream(true).apply {
            environment().apply {
                remove("LD_PRELOAD")
                put("LD_LIBRARY_PATH", lib)
                put("PROOT_LOADER", "$lib/libproot-loader.so")
                put("PROOT_TMP_DIR", tmp.path)
            }
        }
    }

    private fun pump(p: Process) {
        runCatching {
            p.inputStream.bufferedReader().forEachLine { line ->
                _log.value = (_log.value + line).takeLast(MAX_LINES)
            }
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        _log.value = _log.value + "[Linux session ended: exit $code]"
        synchronized(this) {
            if (process === p) {
                process = null
                _running.value = false
            }
        }
    }
}
