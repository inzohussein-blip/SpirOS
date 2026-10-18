package com.spiros.app.engine

import android.content.Context
import java.io.File

/**
 * Runs the QEMU executable bundled by tools/fetch_qemu.py. It lives in
 * nativeLibraryDir as libqemu-system-aarch64.so because that directory is the
 * only app location Android 10+ allows executing from. QEMU runs as a separate
 * process, so a guest crash or exit() can never take SpirOS down with it.
 */
class QemuRunner(private val context: Context) {

    private val libDir: String = context.applicationInfo.nativeLibraryDir
    private val binary = File(libDir, "libqemu-system-aarch64.so")

    val isBundled: Boolean get() = binary.isFile

    fun version(): String = runCatching {
        val process = builder(listOf("-version")).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "?"
    }.getOrElse { "error: ${it.message}" }

    fun start(args: List<String>): Process = builder(args).start()

    private fun builder(args: List<String>) = ProcessBuilder(listOf(binary.path) + args)
        .redirectErrorStream(true)
        .directory(context.filesDir)
        .apply {
            environment()["LD_LIBRARY_PATH"] = libDir
            environment()["TMPDIR"] = context.cacheDir.path
            environment()["HOME"] = context.filesDir.path
        }
}
