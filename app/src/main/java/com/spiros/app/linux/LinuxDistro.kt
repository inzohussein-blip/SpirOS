package com.spiros.app.linux

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The Debian + Xfce root filesystem for the SpirOS Linux desktop. It is built by
 * .github/workflows/linux-rootfs.yml, downloaded from the project's releases,
 * checked against its published SHA-256 and unpacked into app-private storage.
 */
object LinuxDistro {
    private const val BASE = "https://github.com/inzohussein-blip/SpirOS/releases/download/linux-rootfs"
    private const val MARKER = ".spiros-installed"

    enum class Stage { DOWNLOADING, VERIFYING, EXTRACTING }

    /** Debian architecture name for this phone, or null if unsupported. */
    fun arch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "x86_64" -> "amd64"
        else -> null
    }

    fun root(context: Context) = File(context.filesDir, "linux/debian")

    fun isInstalled(context: Context) = File(root(context), MARKER).isFile

    fun remove(context: Context) {
        File(context.filesDir, "linux").deleteRecursively()
    }

    suspend fun install(context: Context, onProgress: (Stage, Float) -> Unit) = withContext(Dispatchers.IO) {
        val arch = arch() ?: error("Unsupported CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        val name = "debian-xfce-$arch.tar.xz"
        val archive = File(context.cacheDir, name)
        try {
            onProgress(Stage.DOWNLOADING, 0f)
            val expected = String(download("$BASE/$name.sha256")).trim().substringBefore(' ')
            downloadTo("$BASE/$name", archive) { onProgress(Stage.DOWNLOADING, it) }

            onProgress(Stage.VERIFYING, 0f)
            val actual = sha256(archive)
            check(actual.equals(expected, ignoreCase = true)) { "Checksum mismatch: the download is damaged or was altered" }

            val root = root(context)
            root.deleteRecursively()
            root.mkdirs()
            extract(archive, root) { onProgress(Stage.EXTRACTING, it) }
            File(root, MARKER).writeText(expected)
        } finally {
            archive.delete()
        }
    }

    // ---- extraction ----------------------------------------------------------------

    private fun extract(archive: File, root: File, onProgress: (Float) -> Unit) {
        val total = archive.length().toFloat()
        val counting = CountingStream(archive.inputStream().buffered(1 shl 16))
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
            var lastReport = 0L
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                // Never write outside the root (malformed or malicious archive). Symlink
                // targets may be absolute: they are resolved inside proot, not here.
                if (name.startsWith("/") || name.split('/').any { it == ".." }) continue
                val out = File(root, name)
                when {
                    entry.isDirectory -> {
                        out.mkdirs()
                        chmod(out, entry.mode or 0b111_000_000)
                    }
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        runCatching { Os.symlink(entry.linkName, out.path) }
                    }
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                        chmod(out, entry.mode or 0b110_000_000)
                    }
                    else -> Unit // device nodes, fifos: provided by proot binds
                }
                if (counting.count - lastReport > 1 shl 20) {
                    lastReport = counting.count
                    onProgress(counting.count / total)
                }
            }
        }
        onProgress(1f)
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.path, mode and 0b111_111_111) }
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    // ---- network ---------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        check(connection.responseCode == 200) { "HTTP ${connection.responseCode} for $url" }
        return connection
    }

    private fun download(url: String): ByteArray {
        val connection = open(url)
        try {
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadTo(url: String, target: File, onProgress: (Float) -> Unit) {
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 1 shl 20) {
                            lastReport = done
                            onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
