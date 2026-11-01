package com.spiros.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.spiros.app.engine.AlpineGuest
import com.spiros.app.engine.LabVm
import com.spiros.app.engine.QemuRunner
import com.spiros.app.engine.VncClient
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * End-to-end Lab test run inside the app process, exactly like the Lab screen:
 * download the guest, build the arguments with LabVm, run the bundled QEMU from
 * nativeLibraryDir, connect the VNC display, and wait for a shell. The outcome is
 * written to no_backup/lab-selftest.log, ending with "RESULT PASS" or "RESULT FAIL".
 */
class LabSelfTestActivity : Activity() {

    private lateinit var log: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "SpirOS Lab self-test running…" })
        log = File(noBackupFilesDir, "lab-selftest.log")
        // One run per process, even if the activity is recreated.
        if (started) return
        started = true
        log.delete()
        thread(name = "lab-selftest") {
            val ok = runCatching { runTest() }.getOrElse { e ->
                write("ERROR ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            write(if (ok) "RESULT PASS" else "RESULT FAIL")
        }
    }

    private fun runTest(): Boolean {
        val runner = QemuRunner(this)
        write("bundled=${runner.isBundled}")
        if (!runner.isBundled) return false
        write("version=${runner.version()}")

        val started = SystemClock.elapsedRealtime()
        val guest = runBlocking { AlpineGuest.ensure(this@LabSelfTestActivity) {} }
        write("guest kernel=${guest.kernel.length()} initrd=${guest.initrd.length()} bytes")

        val args = LabVm.args(this, guest, cores = 2, ramMb = 512, graphics = true)
        write("args=${args.joinToString(" ")}")
        val process = runner.start(args)
        val output = StringBuffer()
        thread(isDaemon = true) {
            process.inputStream.bufferedReader().forEachLine { line ->
                output.append(line).append('\n')
                write("| $line")
            }
        }

        val vnc = VncClient(LabVm.vncSocket(this).path).also { it.start() }
        var vncReported = false
        try {
            while (SystemClock.elapsedRealtime() - started < TIMEOUT_MS) {
                if (!vncReported && vnc.frame.value > 0) {
                    val bmp = vnc.bitmap.value
                    write("VNC_OK ${bmp?.width}x${bmp?.height}")
                    vncReported = true
                }
                if (output.contains("recovery shell")) {
                    write("BOOT_RECOVERY_SHELL: the guest could not finish booting (network/DNS?)")
                    return false
                }
                if (BOOT_MARKERS.any { output.contains(it) }) {
                    write("BOOT_OK after ${(SystemClock.elapsedRealtime() - started) / 1000}s")
                    if (!vncReported) write("VNC_NO_FRAME")
                    return true
                }
                if (!process.isAlive) {
                    write("QEMU_EXITED ${QemuRunner.describeExit(process.exitValue())}")
                    return false
                }
                Thread.sleep(2000)
            }
            write("BOOT_TIMEOUT")
            return false
        } finally {
            vnc.close()
            process.destroy()
        }
    }

    @Synchronized
    private fun write(line: String) {
        log.appendText(line + "\n")
    }

    private companion object {
        @Volatile
        var started = false
        const val TIMEOUT_MS = 15 * 60 * 1000L
        val BOOT_MARKERS = listOf("login:", "Welcome to Alpine")
    }
}
