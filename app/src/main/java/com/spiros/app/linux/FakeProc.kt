package com.spiros.app.linux

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Since Android 8 apps may not read several /proc files that Linux tools expect
 * (top, task managers, panels). These static stand-ins are bound over them by proot.
 */
internal object FakeProc {
    fun prepare(context: Context): List<Pair<File, String>> {
        val dir = File(context.filesDir, "linux/fake-proc").apply { mkdirs() }
        val cores = Runtime.getRuntime().availableProcessors()
        val uptime = SystemClock.elapsedRealtime() / 1000.0
        val files = mapOf(
            "stat" to buildString {
                append("cpu  1000 0 1000 100000 0 0 0 0 0 0\n")
                repeat(cores) { append("cpu$it 100 0 100 10000 0 0 0 0 0 0\n") }
                append("intr 0\nctxt 0\nbtime ${System.currentTimeMillis() / 1000 - uptime.toLong()}\n")
                append("processes 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
            },
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "uptime" to "%.2f %.2f\n".format(uptime, uptime * cores),
            "vmstat" to "nr_free_pages 100000\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
            "version" to "Linux version 6.1.0-spiros (spiros@spiros) #1 SMP PREEMPT\n",
        )
        return files.map { (name, content) ->
            val file = File(dir, name).apply { writeText(content) }
            file to "/proc/$name"
        }
    }
}
