package com.spiros.app.remote

import android.content.Context
import android.os.StatFs
import com.spiros.app.ai.AiServer
import com.spiros.app.linux.LinuxDistro
import com.spiros.app.linux.LinuxSession
import java.io.File

/**
 * The engine's part of a SpirOS problem report: status, storage, the Linux system's
 * build, and the tails of the logs (desktop, AI, MT5/MT4, apt). Logs only, never the
 * user's files.
 */
object EngineDiagnostics {
    private fun tail(file: File, lines: Int = 120): String =
        runCatching { file.readLines().takeLast(lines).joinToString("\n") }.getOrDefault("(unreadable)")

    /** Run [script] in Linux (as root) and return how long it took, or the error. */
    private fun timed(context: Context, script: String): String = runCatching {
        val root = LinuxDistro.root(context)
        val t = System.nanoTime()
        val p = LinuxSession.command(context, root, listOf("/bin/sh", "-c", script)).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        "%.2f s".format((System.nanoTime() - t) / 1e9) + if (out.isNotEmpty()) " ($out)" else ""
    }.getOrElse { "failed: ${it.message}" }

    /**
     * How fast this phone runs Linux: CPU, memory, proot's cost per program start and
     * per file, and plain computing. Compared between reports it shows what is slow.
     */
    private fun speedTest(context: Context): String = buildString {
        val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }.orEmpty().sortedBy { it.name }
        appendLine("cores: ${cpus.size}, max MHz: " + cpus.joinToString(" ") { c ->
            runCatching { (File(c, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() / 1000).toString() }.getOrDefault("?")
        })
        appendLine("now MHz: " + cpus.joinToString(" ") { c ->
            runCatching { (File(c, "cpufreq/scaling_cur_freq").readText().trim().toLong() / 1000).toString() }.getOrDefault("?")
        })
        runCatching {
            val mem = File("/proc/meminfo").readLines().filter { it.startsWith("MemTotal") || it.startsWith("MemAvailable") }
            appendLine(mem.joinToString(", ") { it.replace(Regex("\\s+"), " ") })
        }
        if (!LinuxDistro.isInstalled(context)) { appendLine("(Linux not installed)"); return@buildString }
        appendLine("start Linux once: " + timed(context, "true"))
        appendLine("200 program starts: " + timed(context, "i=0; while [ \$i -lt 200 ]; do /bin/true; i=\$((i+1)); done"))
        appendLine("list 5000 files: " + timed(context, "find /usr/share -maxdepth 6 | head -n 5000 | wc -l"))
        appendLine("compute (shell loop): " + timed(context, "i=0; while [ \$i -lt 300000 ]; do i=\$((i+1)); done; echo \$i"))
    }

    fun report(context: Context, status: String): String = buildString {
        appendLine("== Engine status"); appendLine(status)
        val root = LinuxDistro.root(context)
        appendLine("== Storage")
        runCatching { appendLine("free: ${StatFs(context.filesDir.path).availableBytes / 1048576} MB") }
        if (root.isDirectory) runCatching {
            appendLine("linux build: " + File(root, "etc/spiros-build").readText().trim())
            appendLine("linux desktop: " + File(root, "etc/spiros-desktop").readText().trim())
            appendLine("os: " + File(root, "etc/os-release").readLines().firstOrNull { it.startsWith("PRETTY_NAME") })
        }
        appendLine("addons: " + LinuxDistro.addons(context))
        appendLine("battery limits off (engine): " + context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName))
        appendLine("== Speed test")
        appendLine(speedTest(context))
        appendLine("== Desktop log"); LinuxSession.log.value.takeLast(150).forEach { appendLine(it) }
        appendLine("== AI log"); runCatching { AiServer.log.value.takeLast(80).forEach { appendLine(it) } }
        // Program logs inside Linux, for root and every account.
        val homes = listOf(File(root, "root")) + (File(root, "home").listFiles()?.toList().orEmpty())
        homes.forEach { home ->
            File(home, ".local/share/spiros").listFiles { f -> f.name.endsWith(".log") }?.forEach { log ->
                appendLine("== ${log.relativeTo(root).path}"); appendLine(tail(log))
            }
        }
        File(root, "var/log/apt/history.log").takeIf { it.isFile }?.let { appendLine("== apt history"); appendLine(tail(it, 60)) }
    }
}
