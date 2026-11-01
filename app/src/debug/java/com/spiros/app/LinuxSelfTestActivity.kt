package com.spiros.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.spiros.app.linux.LinuxDistro
import com.spiros.app.linux.LinuxSession
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * End-to-end test of the Linux desktop (speed edition), run inside the app exactly
 * like the Linux screen: download and verify the rootfs, extract it, start proot +
 * Xvnc + Xfce, and wait until the desktop has actually been drawn. The outcome goes
 * to no_backup/linux-selftest.log, ending with "RESULT PASS" or "RESULT FAIL".
 */
class LinuxSelfTestActivity : Activity() {

    private lateinit var log: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "SpirOS Linux self-test running…" })
        log = File(noBackupFilesDir, "linux-selftest.log")
        if (started) return
        started = true
        log.delete()
        thread(name = "linux-selftest") {
            val ok = runCatching { runTest() }.getOrElse { e ->
                write("ERROR ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            LinuxSession.log.value.takeLast(60).forEach { write("| $it") }
            LinuxSession.stop()
            write(if (ok) "RESULT PASS" else "RESULT FAIL")
        }
    }

    private fun runTest(): Boolean {
        write("flavor=${BuildConfig.FLAVOR} arch=${LinuxDistro.arch()} proot=${LinuxSession.prootAvailable(this)}")
        if (BuildConfig.FLAVOR != "speed" || !LinuxSession.prootAvailable(this)) return false

        var t = SystemClock.elapsedRealtime()
        runBlocking {
            var lastStage: LinuxDistro.Stage? = null
            LinuxDistro.install(this@LinuxSelfTestActivity) { stage, _ ->
                if (stage != lastStage) {
                    lastStage = stage
                    write("stage=$stage")
                }
            }
        }
        write("INSTALLED in ${(SystemClock.elapsedRealtime() - t) / 1000}s")

        // proot itself works: run a command in the Debian root.
        val probe = LinuxSession.command(this, LinuxDistro.root(this), listOf("/bin/sh", "-c", "cat /etc/debian_version; uname -m"))
            .start()
        val probeOut = probe.inputStream.bufferedReader().readText().trim()
        write("PROOT exit=${probe.waitFor()} out=${probeOut.replace('\n', ' ')}")

        t = SystemClock.elapsedRealtime()
        LinuxSession.start(this, "1280x720", 120)
        while (SystemClock.elapsedRealtime() - t < TIMEOUT_MS) {
            Thread.sleep(3000)
            val client = LinuxSession.screen.value
            val bitmap = client?.bitmap?.value
            if (client != null && bitmap != null && client.frame.value > 0) {
                // A fresh Xvnc screen is one solid colour; Xfce's panel and desktop are not.
                val colours = HashSet<Int>()
                for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) colours += bitmap.getPixel(x, y)
                if (colours.size > 20) {
                    write("DESKTOP_OK ${bitmap.width}x${bitmap.height} colours=${colours.size} after ${(SystemClock.elapsedRealtime() - t) / 1000}s")
                    return true
                }
            }
            if (!LinuxSession.running.value) {
                write("SESSION_EXITED")
                return false
            }
        }
        write("DESKTOP_TIMEOUT")
        return false
    }

    @Synchronized
    private fun write(line: String) {
        log.appendText(line + "\n")
    }

    private companion object {
        @Volatile
        var started = false
        const val TIMEOUT_MS = 6 * 60 * 1000L
    }
}
