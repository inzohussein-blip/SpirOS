package com.spiros.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.spiros.app.ai.AiChat
import com.spiros.app.ai.AiModels
import com.spiros.app.ai.AiServer
import com.spiros.app.remote.Engine
import com.spiros.app.remote.IEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end test of the private AI through the SpirOS Engine: smallest model
 * download into the engine's Linux system, llama.cpp server inside proot, and one
 * streamed chat answer received by SpirOS (a different app) on 127.0.0.1 with the
 * per-start key. Result in no_backup/ai-selftest.log, ending with "RESULT PASS" or "RESULT FAIL".
 */
class AiSelfTestActivity : Activity() {

    private lateinit var log: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "SpirOS AI self-test running…" })
        log = File(noBackupFilesDir, "ai-selftest.log")
        if (started) return
        started = true
        log.delete()
        thread(name = "ai-selftest") {
            val ok = runCatching { runTest() }.getOrElse { e ->
                write("ERROR ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            engine { it.aiLog() }.orEmpty().takeLast(30).forEach { write("| $it") }
            engine { it.stopAi() }
            write(if (ok) "RESULT PASS" else "RESULT FAIL")
        }
    }

    private fun <T> engine(block: (IEngine) -> T): T? = runBlocking { Engine.call(this@AiSelfTestActivity, block) }

    private fun await(start: (com.spiros.app.remote.IEngineCallback) -> Unit): String? {
        val done = CountDownLatch(1)
        var error: String? = "engine not connected"
        engine { start(Engine.callback(onProgress = { _, _ -> }, onDone = { e -> error = e; done.countDown() })) }
        done.await(20, TimeUnit.MINUTES)
        return error
    }

    private fun runTest(): Boolean {
        val context = this
        write("ram=${AiModels.phoneRamGb(context)}GB recommended=${AiModels.recommended(context).id}")
        if (!Engine.connect(context)) return false
        engine { it.version() }
        Engine.refresh()
        if (!Engine.status.value.linuxInstalled) {
            write("linux install: ${await { cb -> engine { it.installLinux(cb) } } ?: "ok"}")
        }
        val model = AiModels.catalog.first()
        var t = SystemClock.elapsedRealtime()
        val dl = await { cb -> engine { it.downloadModel(model.url, model.fileName, cb) } }
        write("MODEL ${model.id} download=${dl ?: "ok"} in ${(SystemClock.elapsedRealtime() - t) / 1000}s")
        if (dl != null) return false

        t = SystemClock.elapsedRealtime()
        Engine.startAi(context, model)
        Thread.sleep(1000)
        Engine.refresh()
        while (Engine.status.value.aiState == AiServer.State.LOADING && SystemClock.elapsedRealtime() - t < 300_000) {
            Thread.sleep(1000)
            Engine.refresh()
        }
        val state = Engine.status.value.aiState
        write("SERVER $state after ${(SystemClock.elapsedRealtime() - t) / 1000}s")
        if (state != AiServer.State.READY) return false

        t = SystemClock.elapsedRealtime()
        val reply = StringBuilder()
        runBlocking {
            AiChat.complete(context, AiChat.PRESET_GENERAL, listOf(AiChat.Message("user", "What is 2+2? Reply with the number only."))) {
                reply.append(it)
            }
        }
        val seconds = (SystemClock.elapsedRealtime() - t) / 1000.0
        write("REPLY \"${reply.toString().trim().take(200)}\" in ${seconds}s (SpirOS -> engine over 127.0.0.1)")
        return reply.isNotBlank()
    }

    @Synchronized
    private fun write(line: String) {
        log.appendText(line + "\n")
    }

    private companion object {
        @Volatile
        var started = false
    }
}
