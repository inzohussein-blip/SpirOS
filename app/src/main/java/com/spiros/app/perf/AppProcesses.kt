package com.spiros.app.perf

import android.os.Process
import java.io.File

/** SpirOS's own helper processes (Linux, AI, Lab VM); apps can only see their own. */
object AppProcesses {
    data class Info(val pid: Int, val command: String, val rssKb: Long)

    fun list(): List<Info> {
        val me = Process.myPid()
        val uid = Process.myUid()
        return File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == me) return@mapNotNull null
            val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return@mapNotNull null
            val owner = status.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            if (owner != uid) return@mapNotNull null
            val rss = status.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
            val cmd = runCatching { File(dir, "cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")
            Info(pid, cmd, rss)
        }
    }
}
