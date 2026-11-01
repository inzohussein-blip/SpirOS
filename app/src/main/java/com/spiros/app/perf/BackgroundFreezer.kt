package com.spiros.app.perf

import android.os.Process
import java.io.File

/**
 * Freezes SpirOS's Linux programs (desktop, AI, Lab VM) while SpirOS is in the
 * background, so they use no CPU or battery, and resumes them on return.
 * Apps can only see and signal their own processes, so this touches nothing else.
 */
object BackgroundFreezer {
    private const val SIGCONT = 18
    private const val SIGSTOP = 19

    @Volatile
    var frozen = false
        private set

    @Synchronized
    fun freeze() {
        if (frozen) return
        val (tracers, others) = childProcesses().partition { it.second.contains("libproot") }
        // Children first (proot handles their stop), then proot itself.
        others.forEach { Process.sendSignal(it.first, SIGSTOP) }
        tracers.forEach { Process.sendSignal(it.first, SIGSTOP) }
        frozen = true
    }

    @Synchronized
    fun resume() {
        if (!frozen) return
        val (tracers, others) = childProcesses().partition { it.second.contains("libproot") }
        tracers.forEach { Process.sendSignal(it.first, SIGCONT) }
        others.forEach { Process.sendSignal(it.first, SIGCONT) }
        // proot may re-deliver the stop while catching up; continue once more.
        Thread.sleep(150)
        others.forEach { Process.sendSignal(it.first, SIGCONT) }
        frozen = false
    }

    /** (pid, command line) of every process of this app except the app itself. */
    private fun childProcesses(): List<Pair<Int, String>> {
        val me = Process.myPid()
        val uid = Process.myUid()
        return File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == me) return@mapNotNull null
            val owner = runCatching {
                File(dir, "status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
            }.getOrNull()
            if (owner != uid) return@mapNotNull null
            val cmd = runCatching { File(dir, "cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")
            pid to cmd
        }
    }
}
