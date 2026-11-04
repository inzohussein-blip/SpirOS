package com.spiros.app.linux

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the Debian desktop with proot (no emulation: Linux programs run directly on
 * the phone's CPU). Lives in the headless SpirOS Engine app: Android allows running
 * programs from app storage only for apps targeting API 28 or lower, so this code
 * never runs inside the main SpirOS app. SpirOS drives it over Binder (EngineService).
 */
object LinuxSession {
    private const val MAX_LINES = 500

    private var process: Process? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun prootAvailable(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libproot.so").isFile

    /** Desktop options chosen in SpirOS (Settings > Performance & battery). */
    data class Options(val geometry: String, val dpi: Int, val light: Boolean, val singleApp: String?)

    /** Xvnc's private socket; SpirOS gets a connected copy of it through EngineService. */
    fun displaySocket(context: Context) = File(LinuxDistro.root(context), "tmp/spiros-vnc")

    @Synchronized
    fun start(context: Context, options: Options) {
        stop()
        _log.value = emptyList()
        val root = LinuxDistro.root(context)
        installLauncher(context, root)
        registerAndroidGroups(root)
        val socket = File(root, "tmp/spiros-vnc").apply { delete() }
        val p = command(context, root, user = LinuxAccounts.desktopUser(context), envAndCommand = listOf(
            "SPIROS_GEOMETRY=${options.geometry}",
            "SPIROS_DPI=${options.dpi}",
            "SPIROS_LIGHT=${if (options.light) 1 else 0}",
            "SPIROS_SESSION=${if (options.singleApp != null) "app" else "desktop"}",
            "SPIROS_APP=${options.singleApp ?: "xfce4-terminal"}",
            "/usr/local/bin/spiros-desktop",
        )).start()
        process = p
        _running.value = true
        thread(name = "linux-log", isDaemon = true) { pump(p) }
    }

    /** Starts [command] inside the running desktop (display :1) without waiting for it. */
    fun runInDesktop(context: Context, command: String) {
        if (!_running.value) return
        val user = LinuxAccounts.desktopUser(context)
        runCatching {
            val p = command(context, LinuxDistro.root(context), user = user, envAndCommand = listOf(
                "DISPLAY=:1",
                "XDG_RUNTIME_DIR=/tmp/runtime-${user.name}",
                "/bin/sh", "-c", "$command >/dev/null 2>&1",
            )).start()
            // Drain proot's own output so the program never blocks on a full pipe.
            thread(name = "linux-app", isDaemon = true) {
                runCatching { val b = ByteArray(4096); while (p.inputStream.read(b) >= 0) Unit }
            }
        }
    }

    @Synchronized
    fun stop() {
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

    /**
     * Android gives the engine extra group IDs (inet, cache, ...); Linux tools print
     * "cannot find name for group ID" for each. Name them in /etc/group once.
     */
    private fun registerAndroidGroups(root: File) {
        runCatching {
            val gids = File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("Groups:") }?.substringAfter(':')?.trim()
                ?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }.orEmpty()
            val file = File(root, "etc/group")
            if (!file.isFile) return
            val known = file.readLines().mapNotNull { it.split(':').getOrNull(2) }.toSet()
            val missing = gids.distinct().filter { it.toString() !in known }
            if (missing.isNotEmpty()) file.appendText(missing.joinToString("") { "android_$it:x:$it:\n" })
        }
    }

    /** Always run the scripts shipped with this app version (assets/linux). */
    private fun installLauncher(context: Context, root: File) {
        for (name in listOf("spiros-desktop", "spiros-mt5", "spiros-mt4")) {
            val target = File(root, "usr/local/bin/$name")
            target.parentFile?.mkdirs()
            runCatching {
                context.assets.open("linux/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
                target.setExecutable(true, false)
            }
        }
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
