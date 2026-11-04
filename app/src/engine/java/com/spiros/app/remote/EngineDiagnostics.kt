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
