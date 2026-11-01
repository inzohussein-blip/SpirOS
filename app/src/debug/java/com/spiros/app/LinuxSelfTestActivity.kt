package com.spiros.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.spiros.app.remote.Engine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end test of the Linux desktop through the SpirOS Engine, exactly like the
 * Linux screen: SpirOS binds to the engine (signature permission), asks it to
 * download, verify and extract the rootfs, manages accounts, starts proot + Xvnc +
 * Xfce, and draws the desktop from the socket the engine hands over. The outcome
 * goes to no_backup/linux-selftest.log, ending with "RESULT PASS" or "RESULT FAIL".
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
            runBlocking { Engine.call(this@LinuxSelfTestActivity) { it.desktopLog() } }.orEmpty()
                .takeLast(60).forEach { write("| $it") }
            Engine.stopDesktop(this)
            write(if (ok) "RESULT PASS" else "RESULT FAIL")
        }
    }

    private fun <T> engine(block: (com.spiros.app.remote.IEngine) -> T): T? = runBlocking { Engine.call(this@LinuxSelfTestActivity, block) }

    private fun runTest(): Boolean {
        write("engine installed=${Engine.isInstalled(this)}")
        if (!Engine.connect(this)) return false
        val version = engine { it.version() }
        Engine.refresh()
        val status = Engine.status.value
        write("ENGINE version=$version connected=${status.connected} arch=${status.arch} proot=${status.proot}")
        if (!status.connected || !status.proot) return false

        var t = SystemClock.elapsedRealtime()
        val done = CountDownLatch(1)
        var error: String? = null
        var lastStage = ""
        engine {
            it.installLinux(Engine.callback(
                onProgress = { stage, _ -> if (stage != lastStage) { lastStage = stage; write("stage=$stage") } },
                onDone = { e -> error = e; done.countDown() },
            ))
        }
        if (!done.await(15, TimeUnit.MINUTES) || error != null) {
            write("INSTALL_FAILED $error")
            return false
        }
        write("INSTALLED in ${(SystemClock.elapsedRealtime() - t) / 1000}s")

        // Accounts managed from SpirOS: create an admin user, set root's password,
        // and run the desktop as the new user (proot --change-id).
        val added = engine { it.account("add", "spiros", "test1234", true) }
        val rootPw = engine { it.account("password", "root", "root1234", false) }
        val users = Engine.parseUsers(engine { it.users() }).joinToString { "${it.name}(${it.uid}${if (it.admin) ",admin" else ""})" }
        write("ACCOUNTS add=${added ?: "ok"} rootpw=${rootPw ?: "ok"} users=$users")
        if (added == null) engine { it.setDesktopUser("spiros") }
        write("desktop user=${engine { it.desktopUser() }}")

        t = SystemClock.elapsedRealtime()
        Engine.startDesktop(this, "1280x720", dpi = 120, light = true, singleApp = null, fps = 30)
        while (SystemClock.elapsedRealtime() - t < TIMEOUT_MS) {
            Thread.sleep(3000)
            Engine.refresh()
            val client = Engine.screen.value
            val bitmap = client?.bitmap?.value
            if (client != null && bitmap != null && client.frame.value > 0) {
                // A fresh Xvnc screen is one solid colour; Xfce's panel and desktop are not.
                val colours = HashSet<Int>()
                for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) colours += bitmap.getPixel(x, y)
                if (colours.size > 20) {
                    write("DESKTOP_OK ${bitmap.width}x${bitmap.height} colours=${colours.size} after ${(SystemClock.elapsedRealtime() - t) / 1000}s (via engine socket)")
                    // Screenshots for review: the desktop once settled, then the app grid
                    // (right-click on the desktop opens it in SpirOS Linux).
                    Thread.sleep(8000)
                    snapshot(client, "linux-desktop.png")
                    client.rightClick(bitmap.width / 2, bitmap.height / 3)
                    Thread.sleep(5000)
                    snapshot(client, "linux-apps.png")
                    return true
                }
            }
            if (SystemClock.elapsedRealtime() - t > 10_000 && !Engine.status.value.desktopRunning) {
                write("SESSION_EXITED")
                return false
            }
        }
        write("DESKTOP_TIMEOUT")
        return false
    }

    private fun snapshot(client: com.spiros.app.engine.VncClient, name: String) {
        val b = client.bitmap.value ?: return
        runCatching {
            File(noBackupFilesDir, name).outputStream().use { b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            write("SNAPSHOT $name")
        }
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
