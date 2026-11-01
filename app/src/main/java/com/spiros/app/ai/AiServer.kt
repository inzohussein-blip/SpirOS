package com.spiros.app.ai

import android.content.Context
import com.spiros.app.linux.LinuxDistro
import com.spiros.app.linux.LinuxSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Runs llama.cpp's server inside the Linux system (proot, native CPU speed). It
 * listens on 127.0.0.1 only and requires a random key generated per start, so
 * other apps on the phone cannot use it.
 */
object AiServer {
    const val PORT = 18080

    enum class State { STOPPED, LOADING, READY, FAILED }

    private var process: Process? = null

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile
    var apiKey: String = ""
        private set

    /** File name of the running model (in /root/models). */
    @Volatile
    var modelFile: String? = null
        private set

    @Synchronized
    fun start(context: Context, fileName: String, threads: Int, contextSize: Int) {
        stop()
        modelFile = fileName
        apiKey = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        _log.value = emptyList()
        _state.value = State.LOADING
        val p = LinuxSession.command(
            context,
            LinuxDistro.root(context),
            listOf(
                "/usr/local/bin/llama-server",
                "-m", "/root/models/${java.io.File(fileName).name}",
                "--host", "127.0.0.1",
                "--port", "$PORT",
                "--api-key", apiKey,
                "-c", "$contextSize",
                "-t", "$threads",
            ),
        ).start()
        process = p
        thread(name = "ai-log", isDaemon = true) {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line -> _log.value = (_log.value + line).takeLast(200) }
            }
            synchronized(this) {
                if (process === p) {
                    process = null
                    _state.value = State.FAILED
                }
            }
        }
        thread(name = "ai-health", isDaemon = true) { waitUntilReady(p) }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        _state.value = State.STOPPED
    }

    private fun waitUntilReady(p: Process) {
        repeat(600) {
            if (process !== p || !p.isAlive) return
            val ok = runCatching {
                val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
                c.connectTimeout = 1000
                c.readTimeout = 2000
                try {
                    c.responseCode == 200
                } finally {
                    c.disconnect()
                }
            }.getOrDefault(false)
            if (ok) {
                if (process === p) _state.value = State.READY
                return
            }
            Thread.sleep(1000)
        }
    }
}
