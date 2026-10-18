package com.spiros.app.engine

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStreamReader
import kotlin.concurrent.thread

/** One running guest VM and its serial console. Survives navigation, stops on lock. */
object LabSession {
    private const val MAX_LINES = 2000
    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val BOOT_MARKERS = listOf("login:", "Welcome to Alpine", "recovery shell", "~ #", "localhost:~#")

    private var process: Process? = null
    private var startedAt = 0L

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Seconds from start until the guest showed a shell or login prompt. */
    private val _bootSeconds = MutableStateFlow<Double?>(null)
    val bootSeconds: StateFlow<Double?> = _bootSeconds.asStateFlow()

    @Synchronized
    fun start(runner: QemuRunner, args: List<String>) {
        stop()
        _console.value = emptyList()
        _bootSeconds.value = null
        startedAt = SystemClock.elapsedRealtime()
        val p = runner.start(args)
        process = p
        _running.value = true
        thread(name = "qemu-console", isDaemon = true) { pump(p) }
    }

    fun send(line: String) {
        val p = process ?: return
        thread(isDaemon = true) {
            runCatching {
                p.outputStream.write((line + "\n").toByteArray())
                p.outputStream.flush()
            }
        }
    }

    @Synchronized
    fun stop() {
        process?.let { p ->
            p.destroy()
            thread(isDaemon = true) {
                Thread.sleep(2000)
                if (p.isAlive) p.destroyForcibly()
            }
        }
        process = null
        _running.value = false
    }

    private fun pump(p: Process) {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        val buffer = CharArray(4096)
        InputStreamReader(p.inputStream, Charsets.UTF_8).use { reader ->
            while (true) {
                val n = runCatching { reader.read(buffer) }.getOrDefault(-1)
                if (n < 0) break
                for (i in 0 until n) {
                    when (val c = buffer[i]) {
                        '\n' -> {
                            lines += clean(current)
                            current.setLength(0)
                        }
                        '\r' -> Unit
                        else -> current.append(c)
                    }
                }
                while (lines.size > MAX_LINES) lines.removeAt(0)
                val partial = clean(current)
                _console.value = if (partial.isEmpty()) lines.toList() else lines + partial
                if (_bootSeconds.value == null && BOOT_MARKERS.any { m -> partial.contains(m) || lines.lastOrNull()?.contains(m) == true }) {
                    _bootSeconds.value = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
                }
            }
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        _console.value = _console.value + "[QEMU exited with code $code]"
        synchronized(this) {
            if (process === p) {
                process = null
                _running.value = false
            }
        }
    }

    private fun clean(s: CharSequence) = ANSI.replace(s, "")
}
