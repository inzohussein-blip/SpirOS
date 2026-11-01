package com.spiros.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.spiros.app.ai.AiChat
import com.spiros.app.ai.AiModels
import com.spiros.app.ai.AiServer
import com.spiros.app.linux.LinuxDistro
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.concurrent.thread

/**
 * End-to-end test of the private AI (speed edition): Linux system, smallest model
 * download, llama.cpp server inside proot, and one streamed chat answer.
 * Result in no_backup/ai-selftest.log, ending with "RESULT PASS" or "RESULT FAIL".
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
            AiServer.log.value.takeLast(30).forEach { write("| $it") }
            AiServer.stop()
            write(if (ok) "RESULT PASS" else "RESULT FAIL")
        }
    }

    private fun runTest(): Boolean = runBlocking {
        val context = this@AiSelfTestActivity
        write("ram=${AiModels.phoneRamGb(context)}GB recommended=${AiModels.recommended(context).id}")
        if (!LinuxDistro.isInstalled(context)) {
            LinuxDistro.install(context) { _, _ -> }
            write("linux installed")
        }
        val model = AiModels.catalog.first()
        var t = SystemClock.elapsedRealtime()
        AiModels.download(context, model) { }
        write("MODEL ${model.id} downloaded in ${(SystemClock.elapsedRealtime() - t) / 1000}s")

        t = SystemClock.elapsedRealtime()
        AiServer.start(context, model)
        while (AiServer.state.value == AiServer.State.LOADING && SystemClock.elapsedRealtime() - t < 300_000) {
            Thread.sleep(1000)
        }
        write("SERVER ${AiServer.state.value} after ${(SystemClock.elapsedRealtime() - t) / 1000}s")
        if (AiServer.state.value != AiServer.State.READY) return@runBlocking false

        t = SystemClock.elapsedRealtime()
        val reply = StringBuilder()
        AiChat.complete(AiChat.PRESET_GENERAL, listOf(AiChat.Message("user", "What is 2+2? Reply with the number only."))) {
            reply.append(it)
        }
        val seconds = (SystemClock.elapsedRealtime() - t) / 1000.0
        write("REPLY \"${reply.toString().trim().take(200)}\" in ${seconds}s")
        reply.isNotBlank()
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
